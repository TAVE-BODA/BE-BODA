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
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String DONE_STATUS = "DONE";
    private static final String DEFAULT_SESSION_TITLE = "보험 상담 세션";
    private static final String DEFAULT_DISCLAIMER =
            "실제 보험금 지급 여부는 보험사 심사 결과 및 약관에 따라 달라질 수 있습니다.";

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
        QuestionType questionType = request.getQuestionType();

        // 첫 번째 질문이면 설문 데이터로 system_prompt 조립 후 저장
        // 이후 질문부터는 저장된 system_prompt 재사용 (설문 미전송)
        if (chatSession.isFirstMessage()) {
            String systemPrompt = buildSystemPrompt(request);
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

        return chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(chatSessionId)
                .stream()
                .map(ChatMessageResponse::from)
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
                        .title(buildSourceTitle(source))
                        .citedText(buildCitedText(source))
                        .clauseType(source.getClauseType())
                        .relevanceScore(source.getRelevanceScore())
                        .build())
                .toList();

        return ChatMessageSourceResponse.available(messageId, sources);
    }

    private String buildSourceTitle(
            ChatMessageSourceRepository.MessageSourceInfo source
    ) {
        String riderName = source.getRiderName();
        String clauseNo = source.getClauseNo();
        String clauseTitle = source.getClauseTitle();
        String sectionTitle = source.getSectionTitle();

        // 특약명 + 조항 번호 + 조항 제목
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

        // 특약명은 없지만 조항 번호와 제목이 있는 경우
        if (clauseNo != null && !clauseNo.isBlank()
                && clauseTitle != null && !clauseTitle.isBlank()) {

            return clauseNo + " " + clauseTitle;
        }

        // 조항 제목만 있는 경우
        if (clauseTitle != null && !clauseTitle.isBlank()) {
            return clauseTitle;
        }

        // 조항 번호만 있는 경우
        if (clauseNo != null && !clauseNo.isBlank()) {
            return clauseNo;
        }

        // 섹션 제목 사용
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            return sectionTitle;
        }

        return "약관 근거";
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
