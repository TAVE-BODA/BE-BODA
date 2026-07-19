package com.codit.be_boda.chat.service;

import com.codit.be_boda.global.exception.BusinessException;
import com.codit.be_boda.global.exception.ErrorCode;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.ChatSessionCreateRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageResponse;
import com.codit.be_boda.chat.dto.response.ChatSessionResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageSourceResponse;
import com.codit.be_boda.chat.entity.ChatMessage;
import com.codit.be_boda.chat.entity.ChatMessageSource;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatMessageRepository;
import com.codit.be_boda.chat.repository.ChatMessageSourceRepository;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.PolicyAnalysisQueryRepository;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import com.codit.be_boda.chat.type.TreatmentStartDateType;
import com.codit.be_boda.chat.type.TreatmentType;
import com.codit.be_boda.chat.type.DentalTreatmentCountType;
import com.codit.be_boda.chat.validator.ChatMessageRequestValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String DONE_STATUS = "DONE";
    private static final String DEFAULT_SESSION_TITLE = "보험 상담 세션";
    private static final String DEFAULT_DISCLAIMER =
            "실제 보험금 지급 여부는 보험사 심사 결과 및 약관에 따라 달라질 수 있습니다.";

    private static final Pattern CLAUSE_HEADING_PATTERN =
            Pattern.compile(
                    "(제\\s*\\d+(?:-\\d+)?조(?:의\\d+)?"
                            + "\\s*(?:\\[[^\\]\\r\\n]{1,100}]|\\([^\\)\\r\\n]{1,100}\\))?)"
            );

    private static final Pattern APPENDIX_HEADING_PATTERN =
            Pattern.compile(
                    "(별표\\s*\\d+\\s*(?:\\([^\\)\\r\\n]{1,100}\\)|[^\\r\\n]{0,50}분류표))"
            );

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PolicyAnalysisQueryRepository policyAnalysisQueryRepository;
    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ChatMessageRequestValidator chatMessageRequestValidator;
    private final ChatAnswerService chatAnswerService;
    private final ChatMessageSourceRepository chatMessageSourceRepository;

    // case2: analysisIds 포함 → 생성과 동시에 중간 테이블 연결
    // case3: 빈 바디 → 세션만 생성 (업로드 시 chatSessionId로 연결)
    @Transactional
    public ChatSessionResponse createSession(ChatSessionCreateRequest request, Long userId) {
        ChatSession chatSession = new ChatSession(
                userId,
                request.getTermsDocumentId(),
                DEFAULT_SESSION_TITLE
        );
        ChatSession savedSession = chatSessionRepository.save(chatSession);

        List<Long> analysisIds = request.getAnalysisIds();
        if (analysisIds != null && !analysisIds.isEmpty()) {
            List<PolicyAnalysisQueryRepository.PolicyAnalysisInfo> analysisInfos =
                    policyAnalysisQueryRepository.findInfoByAnalysisIds(analysisIds);

            if (analysisInfos.size() != analysisIds.size()) {
                throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
            }

            boolean allDone = analysisInfos.stream()
                    .allMatch(info -> DONE_STATUS.equals(info.analysisStatus()));
            if (!allDone) {
                throw new BusinessException(ErrorCode.ANALYSIS_NOT_DONE);
            }

            for (Long analysisId : analysisIds) {
                chatSessionPolicyRepository.save(
                        new ChatSessionPolicy(savedSession.getChatSessionId(), analysisId)
                );
            }

            return ChatSessionResponse.from(savedSession, analysisIds);
        }

        return ChatSessionResponse.from(savedSession);
    }

    @Transactional
    public ChatMessagePairResponse sendMessage(Long chatSessionId, ChatMessageRequest request) {
        chatMessageRequestValidator.validate(request);

        ChatSession chatSession = findChatSession(chatSessionId);
        QuestionType questionType = resolveQuestionType(request);
        // 첫 번째 질문이면 설문 데이터로 system_prompt 조립 후 저장
        // 이후 질문부터는 저장된 system_prompt 재사용 (설문 미전송)
        if (chatSession.isFirstMessage()
                && questionType != QuestionType.FREE_TEXT) {

            String systemPrompt =
                    buildSystemPrompt(request);

            chatSession.saveSystemPrompt(systemPrompt);
            chatSessionRepository.save(chatSession);
        }

        String userMessageContent = buildUserMessageContent(questionType, request);

        ChatMessage userMessage = new ChatMessage(
                chatSession.getChatSessionId(),
                SenderType.USER,
                questionType,
                userMessageContent,
                false,
                null
        );

        ChatMessage savedUserMessage = chatMessageRepository.save(userMessage);

        ChatAnswerResult aiAnswer = chatAnswerService.generateAnswerResult(chatSession, request);

        ChatMessage aiMessage = new ChatMessage(
                chatSession.getChatSessionId(),
                SenderType.AI,
                questionType,
                aiAnswer.messageContent(),
                false,
                DEFAULT_DISCLAIMER
        );

        ChatMessage savedAiMessage = chatMessageRepository.save(aiMessage);

        saveMessageSources(savedAiMessage.getMessageId(), aiAnswer.sources());

        return ChatMessagePairResponse.of(
                chatSession.getChatSessionId(),
                ChatMessageResponse.from(savedUserMessage),
                ChatMessageResponse.from(
                        savedAiMessage,
                        aiAnswer.claimGuide(),
                        aiAnswer.amountGuide(),
                        aiAnswer.documentGuide(),
                        aiAnswer.hasSources()
                )
        );
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(Long chatSessionId) {
        findChatSession(chatSessionId);

        List<ChatMessage> messages =
                chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(
                        chatSessionId
                );

        if (messages.isEmpty()) {
            return List.of();
        }

        List<Long> messageIds = messages.stream()
                .map(ChatMessage::getMessageId)
                .toList();

        Set<Long> messageIdsWithSources = new HashSet<>(
                chatMessageSourceRepository.findMessageIdsWithSources(
                        messageIds
                )
        );

        return messages
                .stream()
                .map(message ->
                        ChatMessageResponse.from(
                                message,
                                null,
                                null,
                                null,
                                messageIdsWithSources.contains(
                                        message.getMessageId()
                                )
                        )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatMessageSourceResponse getMessageSources(Long messageId) {
        if (messageId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "messageId는 필수입니다.");
        }

        ChatMessage chatMessage = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 메시지입니다."));

        if (chatMessage.getSenderType() != SenderType.AI) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "AI 답변 메시지의 근거만 조회할 수 있습니다.");
        }

        ChatSession chatSession = findChatSession(chatMessage.getChatSessionId());

        if (chatSession.getTermsDocumentId() == null) {
            return ChatMessageSourceResponse.termsNotUploaded(messageId);
        }

        List<ChatMessageSourceRepository.MessageSourceInfo> sourceInfos =
                chatMessageSourceRepository.findSourceInfosByMessageId(messageId);

        if (sourceInfos.isEmpty()) {
            return ChatMessageSourceResponse.sourceNotFound(messageId);
        }

        List<ChatMessageSourceResponse.SourceItem> sources = sourceInfos.stream()
                .map(source -> ChatMessageSourceResponse.SourceItem.builder()
                        .sourceId(source.getSourceId())
                        .chunkId(source.getChunkId())
                        .title(
                                buildSourceTitle(
                                        source,
                                        chatMessage.getQuestionType()
                                )
                        )                        .citedText(buildCitedText(source))
                        .clauseType(source.getClauseType())
                        .relevanceScore(source.getRelevanceScore())
                        .build())
                .toList();

        return ChatMessageSourceResponse.available(messageId, sources);
    }

    private String buildSourceTitle(
            ChatMessageSourceRepository.MessageSourceInfo source,
            QuestionType questionType
    ) {
        String citedText = buildCitedText(source);

        if (questionType == QuestionType.CHIP_CLAIM
                && isDentalExtractionExclusionSource(citedText)) {
            return "보장 대상이 되지 않는 영구치 발치의 원인";
        }

        if (questionType == QuestionType.CHIP_DOCUMENTS) {
            String documentSourceTitle =
                    buildDocumentSourceTitle(citedText);

            if (documentSourceTitle != null) {
                return documentSourceTitle;
            }
        }

        if (questionType == QuestionType.CHIP_AMOUNT) {
            String amountSourceTitle =
                    buildAmountSourceTitle(citedText);

            if (amountSourceTitle != null) {
                return amountSourceTitle;
            }
        }

        // 모든 질문 유형에서 실제 근거 본문의 조항명을 가장 먼저 사용
        String contentTitle =
                extractSourceTitleFromText(
                        citedText
                );

        if (contentTitle != null
                && !contentTitle.isBlank()) {
            return contentTitle;
        }

        return buildMetadataSourceTitle(source);
    }

    private String buildDocumentSourceTitle(
            String citedText
    ) {
        if (citedText == null || citedText.isBlank()) {
            return null;
        }

        String normalizedText = citedText
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "");

        if (normalizedText.contains("치과치료관련증명서")
                && (normalizedText.contains("치과치료확인서")
                || normalizedText.contains("치과진료기록")
                || normalizedText.contains("Xray사진"))) {
            return "치과치료 관련 증명서";
        }

        if (normalizedText.contains("사고보험금청구서류대표예시")
                || (normalizedText.contains("청구서")
                && normalizedText.contains("신분증")
                && normalizedText.contains("구비서류"))) {
            return "사고보험금 청구서류 대표예시";
        }

        return null;
    }

    private String buildAmountSourceTitle(
            String citedText
    ) {
        if (citedText == null || citedText.isBlank()) {
            return null;
        }

        String normalizedText = citedText
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "");

        if (normalizedText.contains("2인실입원상급종합병원")
                || normalizedText.contains("3인실입원상급종합병원")) {
            return "2·3인실 입원(상급종합병원) 보험금 지급사유";
        }

        if (normalizedText.contains("2인실입원종합병원이상")
                || normalizedText.contains("3인실입원종합병원이상")
                || normalizedText.contains("상급종합병원혹은종합병원")) {
            return "2·3인실 입원(종합병원 이상) 보험금 지급사유";
        }

        if (normalizedText.contains("재해골절진단보험금")
                && normalizedText.contains("깁스부목제외치료보험금")) {
            return "재해골절·깁스치료 보험금 지급사유";
        }

        if (normalizedText.contains("재해골절진단보험금")) {
            return "재해골절진단보험금 지급사유";
        }

        if (normalizedText.contains("깁스부목제외치료보험금")) {
            return "깁스치료보험금 지급사유";
        }

        return null;
    }

    private boolean isDentalExtractionExclusionSource(
            String citedText
    ) {
        if (citedText == null || citedText.isBlank()) {
            return false;
        }

        String normalizedText = citedText
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "");

        return normalizedText.contains("제3대구치사랑니를발치")
                || normalizedText.contains("부분매복되거나,완전매복되어발치")
                || normalizedText.contains("부분매복되거나완전매복되어발치");
    }

    private String extractSourceTitleFromText(String citedText) {
        if (citedText == null || citedText.isBlank()) {
            return null;
        }

        Matcher clauseMatcher =
                CLAUSE_HEADING_PATTERN.matcher(citedText);

        if (clauseMatcher.find()) {
            return clauseMatcher.group(1)
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        Matcher appendixMatcher =
                APPENDIX_HEADING_PATTERN.matcher(citedText);

        if (appendixMatcher.find()) {
            return appendixMatcher.group(1)
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        return null;
    }

    private String buildMetadataSourceTitle(
            ChatMessageSourceRepository.MessageSourceInfo source
    ) {
        String riderName = source.getRiderName();
        String clauseNo = source.getClauseNo();
        String clauseTitle = source.getClauseTitle();
        String sectionTitle = source.getSectionTitle();

        if (riderName != null && !riderName.isBlank()
                && clauseNo != null && !clauseNo.isBlank()) {

            StringBuilder title = new StringBuilder();

            title.append(riderName)
                    .append(" ")
                    .append(clauseNo);

            if (clauseTitle != null && !clauseTitle.isBlank()) {
                title.append(" ").append(clauseTitle);
            }

            return title.toString();
        }

        if (clauseNo != null && !clauseNo.isBlank()
                && clauseTitle != null && !clauseTitle.isBlank()) {

            return clauseNo + " " + clauseTitle;
        }

        if (clauseTitle != null && !clauseTitle.isBlank()) {
            return clauseTitle;
        }

        if (clauseNo != null && !clauseNo.isBlank()) {
            return clauseNo;
        }

        if (sectionTitle != null && !sectionTitle.isBlank()) {
            return sectionTitle;
        }

        String textTitle =
                buildTextFallbackTitle(
                        buildCitedText(source)
                );

        if (textTitle != null) {
            return textTitle;
        }

        return "보험약관 조항";
    }

    private String buildTextFallbackTitle(String citedText) {
        if (citedText == null || citedText.isBlank()) {
            return null;
        }

        String[] lines =
                citedText.split("\\R");

        for (String line : lines) {
            String normalizedLine =
                    line.replaceAll("\\s+", " ")
                            .trim();

            if (normalizedLine.isBlank()) {
                continue;
            }

            if (normalizedLine.length() <= 80) {
                return normalizedLine;
            }

            return normalizedLine.substring(0, 80)
                    + "...";
        }

        return null;
    }

    private String buildCitedText(ChatMessageSourceRepository.MessageSourceInfo source) {
        if (source.getCitedText() != null && !source.getCitedText().isBlank()) {
            return source.getCitedText();
        }
        return source.getChunkText();
    }

    private void saveMessageSources(Long messageId, List<AnswerSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        List<ChatMessageSource> messageSources = sources.stream()
                .filter(source -> source.chunkId() != null)
                .map(source -> new ChatMessageSource(
                        messageId,
                        source.chunkId(),
                        source.citedText(),
                        source.relevanceScore()
                ))
                .toList();

        if (messageSources.isEmpty()) {
            return;
        }

        chatMessageSourceRepository.saveAll(messageSources);
    }

    private ChatSession findChatSession(Long chatSessionId) {
        if (chatSessionId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "chatSessionId는 필수입니다.");
        }
        return chatSessionRepository.findById(chatSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    // TODO: 설문 수정시 변경되어야될 부분 이쪽.
    private String buildSystemPrompt(ChatMessageRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("[사용자 상황 정보]\n");

        if (request.getIncidentType() != null) {
            String incidentLabel = switch (request.getIncidentType()) {
                case INJURY        -> "다쳤어요 (재해)";
                case DISEASE       -> "아파서 병원에 갔어요 (질병)";
                case CHECKUP_FOUND -> "검진에서 발견됐어요";
            };
            sb.append("- 발생 상황: ").append(incidentLabel).append("\n");
        }

        if (request.getTreatmentTypes() != null && !request.getTreatmentTypes().isEmpty()) {
            sb.append("- 받은 치료: ");
            StringJoiner joiner = new StringJoiner(", ");
            for (TreatmentType t : request.getTreatmentTypes()) {
                String label = switch (t) {
                    case DIAGNOSIS_ONLY  -> "진단만 받았어요";
                    case SURGERY         -> "수술";
                    case HOSPITALIZATION -> "입원";
                    case OUTPATIENT      -> "통원/외래";
                    case CAST            -> "깁스/고정";
                    case DENTAL          -> "치아 치료";
                    case DISABILITY      -> "장해/후유장해";
                };
                joiner.add(label);
            }
            sb.append(joiner).append("\n");
        }

        if (request.getHospitalizationInfo() != null) {
            sb.append("- 입원 정보: ")
                    .append("병원 종류=").append(request.getHospitalizationInfo().getHospitalType())
                    .append(", 병실=").append(request.getHospitalizationInfo().getRoomType())
                    .append(", 입원 기간=").append(request.getHospitalizationInfo().getHospitalizedNights()).append("박\n");
        }

        if (request.getCastInfo() != null) {
            sb.append("- 깁스 정보: ")
                    .append("부위=").append(request.getCastInfo().getCastInjuryPartType())
                    .append(", 깁스 방식=").append(request.getCastInfo().getCastType()).append("\n");
        }

        if (request.getDentalInfo() != null
                && request.getDentalInfo().getDentalTreatmentTypes() != null
                && !request.getDentalInfo().getDentalTreatmentTypes().isEmpty()) {

            sb.append("- 치아 치료: ")
                    .append(request.getDentalInfo().getDentalTreatmentTypes())
                    .append("\n");

            if (request.getDentalInfo().getDentalTreatmentCountType()
                    == DentalTreatmentCountType.EXACT_COUNT
                    && request.getDentalInfo().getDentalTreatmentCount() != null) {

                sb.append("- 치료 치아 개수: ")
                        .append(request.getDentalInfo().getDentalTreatmentCount())
                        .append("개\n");
            }
        }

        if (request.getTreatmentStartDateType() != null) {
            if (request.getTreatmentStartDateType() == TreatmentStartDateType.EXACT_DATE) {
                sb.append("- 치료 시작일: ").append(request.getTreatmentStartDate()).append("\n");
            } else if (request.getTreatmentStartDateType() == TreatmentStartDateType.YEAR_MONTH) {
                sb.append("- 치료 시작 시점: ")
                        .append(request.getTreatmentStartYear()).append("년 ")
                        .append(request.getTreatmentStartMonth()).append("월\n");
            }
        }

        return sb.toString().trim();
    }

    private String buildUserMessageContent(QuestionType questionType, ChatMessageRequest request) {
        StringBuilder builder = new StringBuilder();

        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            builder.append(request.getMessage()).append("\n\n");
        } else {
            builder.append(getDefaultUserQuestion(questionType)).append("\n\n");
        }

        builder.append("[사용자 입력 조건]\n");

        if (request.getIncidentType() != null) {
            builder.append("- 발생 상황: ").append(request.getIncidentType()).append("\n");
        }

        if (request.getTreatmentTypes() != null && !request.getTreatmentTypes().isEmpty()) {
            builder.append("- 받은 치료: ")
                    .append(joinTreatmentTypes(request.getTreatmentTypes()))
                    .append("\n");
        }

        if (request.getHospitalizationInfo() != null) {
            builder.append("- 입원 정보: ")
                    .append("병원 종류=").append(request.getHospitalizationInfo().getHospitalType())
                    .append(", 병실=").append(request.getHospitalizationInfo().getRoomType())
                    .append(", 입원 기간=").append(request.getHospitalizationInfo().getHospitalizedNights()).append("박\n");
        }

        if (request.getCastInfo() != null) {
            builder.append("- 깁스 정보: ")
                    .append("부위=").append(request.getCastInfo().getCastInjuryPartType())
                    .append(", 깁스 방식=").append(request.getCastInfo().getCastType()).append("\n");
        }

        if (request.getDentalInfo() != null
                && request.getDentalInfo().getDentalTreatmentTypes() != null
                && !request.getDentalInfo().getDentalTreatmentTypes().isEmpty()) {

            builder.append("- 치아 치료: ")
                    .append(request.getDentalInfo().getDentalTreatmentTypes())
                    .append("\n");

            if (request.getDentalInfo().getDentalTreatmentCountType()
                    == DentalTreatmentCountType.EXACT_COUNT
                    && request.getDentalInfo().getDentalTreatmentCount() != null) {

                builder.append("- 치료 치아 개수: ")
                        .append(request.getDentalInfo().getDentalTreatmentCount())
                        .append("개\n");
            }
        }
        appendTreatmentStartDate(builder, request);

        return builder.toString().trim();
    }

    private String getDefaultUserQuestion(QuestionType questionType) {
        return switch (questionType) {
            case CHIP_CLAIM     -> "청구 가능한지 먼저 알고 싶어요";
            case CHIP_AMOUNT    -> "예상 보험금을 먼저 알고 싶어요";
            case CHIP_DOCUMENTS -> "필요 서류를 먼저 알고 싶어요";
            case CHIP_OVERVIEW  -> "내 보험의 보장 항목부터 보고싶어요";
            case FREE_TEXT      -> "직접 입력할게요";
        };
    }

    private QuestionType resolveQuestionType(
            ChatMessageRequest request
    ) {
        if (request.getQuestionType() != null) {
            return request.getQuestionType();
        }

        if (request.getMessage() != null
                && !request.getMessage().isBlank()) {
            return QuestionType.FREE_TEXT;
        }

        return null;
    }

    private String joinTreatmentTypes(List<TreatmentType> treatmentTypes) {
        StringJoiner joiner = new StringJoiner(", ");
        for (TreatmentType treatmentType : treatmentTypes) {
            joiner.add(treatmentType.name());
        }
        return joiner.toString();
    }

    private void appendTreatmentStartDate(StringBuilder builder, ChatMessageRequest request) {
        if (request.getTreatmentStartDateType() == null) return;
        if (request.getTreatmentStartDateType() == TreatmentStartDateType.EXACT_DATE) {
            builder.append("- 치료 시작일: ").append(request.getTreatmentStartDate()).append("\n");
        } else if (request.getTreatmentStartDateType() == TreatmentStartDateType.YEAR_MONTH) {
            builder.append("- 치료 시작 시점: ")
                    .append(request.getTreatmentStartYear()).append("년 ")
                    .append(request.getTreatmentStartMonth()).append("월\n");
        }
    }

    private String generateOverviewAnswer(Long chatSessionId) {
        List<Long> analysisIds = chatSessionPolicyRepository
                .findByChatSessionId(chatSessionId)
                .stream()
                .map(ChatSessionPolicy::getAnalysisId)
                .toList();

        if (analysisIds.isEmpty()) {
            return "현재 채팅방에 연결된 증권이 없습니다.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("현재 증권에서 확인된 보장 카드 목록입니다.\n\n");

        for (Long analysisId : analysisIds) {
            List<CoverageItemQueryRepository.CoverageItemInfo> coverageItems =
                    coverageItemQueryRepository.findByAnalysisId(analysisId);

            if (coverageItems.isEmpty()) continue;

            if (analysisIds.size() > 1) {
                builder.append("[증권 ID: ").append(analysisId).append("]\n");
            }

            for (CoverageItemQueryRepository.CoverageItemInfo item : coverageItems) {
                builder.append("- ")
                        .append(item.coverageType())
                        .append(" / 탐지 여부: ")
                        .append(item.isDetected())
                        .append("\n");
                if (item.detail() != null && !item.detail().isBlank()) {
                    builder.append("  세부 정보: ").append(item.detail()).append("\n");
                }
            }
            builder.append("\n");
        }

        return builder.toString().isBlank()
                ? "현재 증권 분석 결과에서 조회 가능한 보장 카드가 없습니다."
                : builder.toString().trim();
    }
}
