package com.codit.be_boda.chat.service.answer.freetext;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.entity.ChatMessage;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.repository.ChatMessageRepository;
import com.codit.be_boda.chat.repository.ChatMessageSourceRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
import com.codit.be_boda.chat.service.AnswerSource;
import com.codit.be_boda.chat.service.ChatAnswerResult;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeTextAnswerGenerator {

    private static final int SEARCH_LIMIT = 40;
    private static final int SOURCE_LIMIT = 3;
    private static final int MAX_CONTEXT_LENGTH = 2_000;

    private final OpenAiChatModel chatModel;
    private final FreeTextIntentClassifier intentClassifier;
    private final FreeTextPromptBuilder promptBuilder;
    private final TermsChunkQueryRepository termsChunkQueryRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageSourceRepository chatMessageSourceRepository;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    public ChatAnswerResult generate(
            ChatSession chatSession,
            ChatMessageRequest request
    ) {
        String question = request.getMessage();

        FreeTextIntentClassifier.Intent intent =
                intentClassifier.classify(question);

        return switch (intent) {
            case AMBIGUOUS -> buildAmbiguousAnswer();
            case OUT_OF_SCOPE -> buildOutOfScopeAnswer();
            case TERM_DEFINITION ->
                    generateTermsAnswer(
                            chatSession,
                            question,
                            intent
                    );
            case EXCLUSION_EXPLANATION ->
                    generateTermsAnswer(
                            chatSession,
                            question,
                            intent
                    );
            case AMOUNT_EXPLANATION ->
                    generateAmountExplanation(
                            chatSession,
                            question
                    );
            case UNKNOWN -> buildUnknownAnswer();
        };
    }

    private ChatAnswerResult generateTermsAnswer(
            ChatSession chatSession,
            String question,
            FreeTextIntentClassifier.Intent intent
    ) {
        if (chatSession.getTermsDocumentId() == null) {
            return ChatAnswerResult.text(
                    "가입하신 증권의 보장 정보는 확인할 수 있지만, "
                            + "구체적인 정의와 지급 조건을 확인하려면 약관 업로드가 필요해요."
            );
        }

        List<String> keywords =
                intentClassifier.extractSearchKeywords(question);

        List<TermsChunkInfo> chunks =
                termsChunkQueryRepository
                        .findByTermsDocumentIdAndKeywords(
                                chatSession.getTermsDocumentId(),
                                keywords,
                                SEARCH_LIMIT
                        );

        List<TermsChunkInfo> selectedChunks =
                selectRelevantChunks(
                        chunks,
                        keywords,
                        intent
                );

        if (selectedChunks.isEmpty()) {
            return ChatAnswerResult.text(
                    "연결된 약관에서 질문과 직접 관련된 조항을 찾지 못했어요.\n\n"
                            + "보장명이나 약관 용어를 조금 더 구체적으로 입력해 주세요."
            );
        }

        String termsContext =
                buildTermsContext(selectedChunks);

        String prompt =
                intent == FreeTextIntentClassifier.Intent.TERM_DEFINITION
                        ? promptBuilder.buildDefinitionPrompt(
                        question,
                        termsContext
                )
                        : promptBuilder.buildExclusionPrompt(
                        question,
                        termsContext
                );

        List<AnswerSource> sources =
                buildAnswerSources(selectedChunks);

        String fallbackMessage =
                intent == FreeTextIntentClassifier.Intent.TERM_DEFINITION
                        ? "관련 약관 조항은 찾았지만, 용어 설명을 생성하지 못했어요. 약관 근거에서 상세 내용을 확인해 주세요."
                        : "관련 약관 조항은 찾았지만, 제외 이유 설명을 생성하지 못했어요. 약관 근거에서 상세 내용을 확인해 주세요.";

        String answer = callLlm(prompt, fallbackMessage);

        return ChatAnswerResult.text(
                answer,
                !sources.isEmpty(),
                sources
        );
    }

    private ChatAnswerResult generateAmountExplanation(
            ChatSession chatSession,
            String question
    ) {
        ChatMessage recentAmountMessage =
                findRecentAmountMessage(
                        chatSession.getChatSessionId()
                );

        if (recentAmountMessage == null) {
            return ChatAnswerResult.text(
                    "최근 예상 보험금 계산 결과를 찾지 못했어요.\n\n"
                            + "먼저 예상 보험금 칩에서 조건을 입력한 뒤 다시 질문해 주세요."
            );
        }

        List<AnswerSource> sources =
                findSavedSources(
                        recentAmountMessage.getMessageId()
                );

        String termsContext =
                buildSavedSourceContext(
                        recentAmountMessage.getMessageId()
                );

        String prompt =
                promptBuilder.buildAmountExplanationPrompt(
                        question,
                        chatSession.getSystemPrompt(),
                        recentAmountMessage.getMessageContent(),
                        termsContext
                );

        String answer = callLlm(
                prompt,
                "최근 예상 보험금 결과에 적용된 사용자 조건과 보장금액을 기준으로 계산됐어요. "
                        + "정확한 지급 여부는 약관 조건과 보험사 심사 결과에 따라 달라질 수 있어요."
        );

        return ChatAnswerResult.text(
                answer,
                !sources.isEmpty(),
                sources
        );
    }

    private ChatMessage findRecentAmountMessage(Long chatSessionId) {
        List<ChatMessage> messages =
                chatMessageRepository
                        .findByChatSessionIdOrderByCreatedAtAsc(
                                chatSessionId
                        );

        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);

            if (message.getSenderType() == SenderType.AI
                    && message.getQuestionType()
                    == QuestionType.CHIP_AMOUNT) {

                return message;
            }
        }

        return null;
    }

    private List<TermsChunkInfo> selectRelevantChunks(
            List<TermsChunkInfo> chunks,
            List<String> keywords,
            FreeTextIntentClassifier.Intent intent
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Comparator<TermsChunkInfo> scoreComparator =
                Comparator.comparingInt(
                        chunk -> calculateChunkScore(
                                chunk,
                                keywords,
                                intent
                        )
                );

        return chunks.stream()
                .filter(this::isUsableChunk)
                .sorted(scoreComparator.reversed())
                .limit(SOURCE_LIMIT)
                .toList();
    }

    private boolean isUsableChunk(TermsChunkInfo chunk) {
        String text = valueOrEmpty(chunk.chunkText());
        String title = valueOrEmpty(chunk.clauseTitle())
                + " "
                + valueOrEmpty(chunk.sectionTitle());

        if (text.isBlank()) {
            return false;
        }

        if (text.contains("사고보험금 청구서류 대표예시")
                || text.contains("청구서(회사양식)")
                || text.contains("선지급 치료비")) {

            return false;
        }

        return !title.contains("선지급 치료비");
    }

    private int calculateChunkScore(
            TermsChunkInfo chunk,
            List<String> keywords,
            FreeTextIntentClassifier.Intent intent
    ) {
        String searchableText =
                normalize(
                        valueOrEmpty(chunk.clauseTitle())
                                + " "
                                + valueOrEmpty(chunk.sectionTitle())
                                + " "
                                + valueOrEmpty(chunk.chunkText())
                );

        int score = 0;

        for (String keyword : keywords) {
            if (searchableText.contains(normalize(keyword))) {
                score += 10;
            }
        }

        if (intent == FreeTextIntentClassifier.Intent.TERM_DEFINITION
                && searchableText.contains("정의")) {
            score += 8;
        }

        if (intent
                == FreeTextIntentClassifier.Intent.EXCLUSION_EXPLANATION) {

            if (searchableText.contains("제외")
                    || searchableText.contains("지급하지않")
                    || searchableText.contains("부목")) {
                score += 8;
            }
        }

        if (searchableText.contains("지급사유")) {
            score += 3;
        }

        return score;
    }

    private String buildTermsContext(List<TermsChunkInfo> chunks) {
        StringBuilder context = new StringBuilder();

        for (int index = 0; index < chunks.size(); index++) {
            TermsChunkInfo chunk = chunks.get(index);

            context.append("[약관 근거 ")
                    .append(index + 1)
                    .append("]\n");

            if (chunk.clauseNo() != null) {
                context.append("조항 번호: ")
                        .append(chunk.clauseNo())
                        .append("\n");
            }

            if (chunk.clauseTitle() != null) {
                context.append("조항 제목: ")
                        .append(chunk.clauseTitle())
                        .append("\n");
            }

            context.append(limitText(chunk.chunkText()))
                    .append("\n\n");
        }

        return context.toString().trim();
    }

    private List<AnswerSource> buildAnswerSources(
            List<TermsChunkInfo> chunks
    ) {
        Map<Long, AnswerSource> uniqueSources =
                new LinkedHashMap<>();

        for (TermsChunkInfo chunk : chunks) {
            if (chunk.chunkId() == null) {
                continue;
            }

            uniqueSources.putIfAbsent(
                    chunk.chunkId(),
                    new AnswerSource(
                            chunk.chunkId(),
                            null,
                            null
                    )
            );
        }

        return new ArrayList<>(uniqueSources.values());
    }

    private List<AnswerSource> findSavedSources(Long messageId) {
        Map<Long, AnswerSource> uniqueSources =
                new LinkedHashMap<>();

        chatMessageSourceRepository
                .findSourceInfosByMessageId(messageId)
                .forEach(source -> {
                    if (source.getChunkId() == null) {
                        return;
                    }

                    uniqueSources.putIfAbsent(
                            source.getChunkId(),
                            new AnswerSource(
                                    source.getChunkId(),
                                    null,
                                    source.getRelevanceScore()
                            )
                    );
                });

        return new ArrayList<>(uniqueSources.values());
    }

    private String buildSavedSourceContext(Long messageId) {
        StringBuilder context = new StringBuilder();

        List<ChatMessageSourceRepository.MessageSourceInfo> sourceInfos =
                chatMessageSourceRepository
                        .findSourceInfosByMessageId(messageId);

        int sourceCount =
                Math.min(
                        sourceInfos.size(),
                        SOURCE_LIMIT
                );

        for (int index = 0; index < sourceCount; index++) {
            ChatMessageSourceRepository.MessageSourceInfo source =
                    sourceInfos.get(index);

            String sourceText =
                    firstNotBlank(
                            source.getCitedText(),
                            source.getChunkText()
                    );

            context.append("[약관 근거 ")
                    .append(index + 1)
                    .append("]\n")
                    .append(limitText(sourceText))
                    .append("\n\n");
        }

        return context.toString().trim();
    }

    private String callLlm(
            String prompt,
            String fallbackMessage
    ) {
        try {
            String response =
                    ChatClient.builder(chatModel)
                            .build()
                            .prompt()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .model(miniModel)
                                            .temperature(0.0)
                                            .build()
                            )
                            .user(prompt)
                            .call()
                            .content();

            if (response == null || response.isBlank()) {
                return fallbackMessage;
            }

            return response.trim();
        } catch (Exception exception) {
            log.error(
                    "[FREE_TEXT] 답변 생성 실패 | {}",
                    exception.getMessage(),
                    exception
            );

            return fallbackMessage;
        }
    }

    private ChatAnswerResult buildAmbiguousAnswer() {
        return ChatAnswerResult.text(
                "어떤 보장이나 결과를 말씀하시는지 확인하기 어려워요.\n\n"
                        + "궁금한 보장명을 함께 입력해 주세요. "
                        + "예: “5대 재해골절이 뭐야?”"
        );
    }

    private ChatAnswerResult buildOutOfScopeAnswer() {
        return ChatAnswerResult.text(
                "현재 챗봇은 연결된 보험 증권과 약관을 기준으로 "
                        + "보장, 예상 보험금 및 청구 서류를 안내할 수 있어요.\n\n"
                        + "치료 방법은 의료진에게, 법률적인 판단은 관련 전문기관에 확인해 주세요."
        );
    }

    private ChatAnswerResult buildUnknownAnswer() {
        return ChatAnswerResult.text(
                "현재는 보험 용어, 예상 보험금 계산 이유, "
                        + "지급 제외 이유에 관한 질문을 안내할 수 있어요.\n\n"
                        + "예: “보험연도가 뭐야?”, “왜 1년 이내 금액이 적용됐어?”"
        );
    }

    private String limitText(String text) {
        if (text == null) {
            return "";
        }

        if (text.length() <= MAX_CONTEXT_LENGTH) {
            return text;
        }

        return text.substring(0, MAX_CONTEXT_LENGTH);
    }

    private String firstNotBlank(
            String first,
            String second
    ) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        return valueOrEmpty(second);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return valueOrEmpty(value)
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "")
                .toLowerCase();
    }
}