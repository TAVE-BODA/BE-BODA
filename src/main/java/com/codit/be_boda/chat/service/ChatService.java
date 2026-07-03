package com.codit.be_boda.chat.service;

import com.codit.be_boda.global.exception.BusinessException;
import com.codit.be_boda.global.exception.ErrorCode;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.ChatSessionCreateRequest;
import com.codit.be_boda.chat.dto.response.ChatMessagePairResponse;
import com.codit.be_boda.chat.dto.response.ChatMessageResponse;
import com.codit.be_boda.chat.dto.response.ChatSessionResponse;
import com.codit.be_boda.chat.entity.ChatMessage;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.repository.ChatMessageRepository;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.PolicyAnalysisQueryRepository;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import com.codit.be_boda.chat.type.TreatmentStartDateType;
import com.codit.be_boda.chat.type.TreatmentType;
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
    private final ChatMessageRepository chatMessageRepository;
    private final PolicyAnalysisQueryRepository policyAnalysisQueryRepository;
    private final CoverageItemQueryRepository coverageItemQueryRepository;

    @Transactional
    public ChatSessionResponse createSession(ChatSessionCreateRequest request) {
        if (request.getAnalysisId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "analysisId는 필수입니다.");
        }

        PolicyAnalysisQueryRepository.PolicyAnalysisInfo analysisInfo =
                policyAnalysisQueryRepository.findInfoByAnalysisId(request.getAnalysisId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));

        if (!DONE_STATUS.equals(analysisInfo.analysisStatus())) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_DONE);
        }

        ChatSession chatSession = new ChatSession(
                analysisInfo.userId(),
                analysisInfo.analysisId(),
                request.getTermsDocumentId(),
                DEFAULT_SESSION_TITLE
        );

        ChatSession savedSession = chatSessionRepository.save(chatSession);

        return ChatSessionResponse.from(savedSession);
    }

    @Transactional
    public ChatMessagePairResponse sendMessage(Long chatSessionId, ChatMessageRequest request) {
        ChatSession chatSession = findChatSession(chatSessionId);

        QuestionType questionType = resolveQuestionType(request.getQuestionType());

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

        String aiAnswer = generateAiAnswer(chatSession, questionType, request);

        ChatMessage aiMessage = new ChatMessage(
                chatSession.getChatSessionId(),
                SenderType.AI,
                questionType,
                aiAnswer,
                false,
                DEFAULT_DISCLAIMER
        );

        ChatMessage savedAiMessage = chatMessageRepository.save(aiMessage);

        return ChatMessagePairResponse.of(
                chatSession.getChatSessionId(),
                ChatMessageResponse.from(savedUserMessage),
                ChatMessageResponse.from(savedAiMessage)
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

    private ChatSession findChatSession(Long chatSessionId) {
        if (chatSessionId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "chatSessionId는 필수입니다.");
        }

        return chatSessionRepository.findById(chatSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private QuestionType resolveQuestionType(QuestionType questionType) {
        if (questionType == null) {
            return QuestionType.FREE_TEXT;
        }

        return questionType;
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
                    .append("병원 종류=")
                    .append(request.getHospitalizationInfo().getHospitalType())
                    .append(", 병실=")
                    .append(request.getHospitalizationInfo().getRoomType())
                    .append(", 입원 기간=")
                    .append(request.getHospitalizationInfo().getHospitalizedNights())
                    .append("박\n");
        }

        if (request.getCastInfo() != null) {
            builder.append("- 깁스 정보: ")
                    .append("부위=")
                    .append(request.getCastInfo().getCastInjuryPartType())
                    .append(", 깁스 방식=")
                    .append(request.getCastInfo().getCastType())
                    .append("\n");
        }

        if (request.getDentalInfo() != null
                && request.getDentalInfo().getDentalTreatmentTypes() != null
                && !request.getDentalInfo().getDentalTreatmentTypes().isEmpty()) {
            builder.append("- 치아 치료: ")
                    .append(request.getDentalInfo().getDentalTreatmentTypes())
                    .append("\n");
        }

        appendTreatmentStartDate(builder, request);

        return builder.toString().trim();
    }

    private String getDefaultUserQuestion(QuestionType questionType) {
        return switch (questionType) {
            case CHIP_CLAIM -> "청구 가능한지 먼저 알고 싶어요";
            case CHIP_AMOUNT -> "예상 보험금을 먼저 알고 싶어요";
            case CHIP_DOCUMENTS -> "필요 서류를 먼저 알고 싶어요";
            case CHIP_OVERVIEW -> "내 보험의 보장 항목부터 보고싶어요";
            case FREE_TEXT -> "직접 입력할게요";
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
        if (request.getTreatmentStartDateType() == null) {
            return;
        }

        if (request.getTreatmentStartDateType() == TreatmentStartDateType.EXACT_DATE) {
            builder.append("- 치료 시작일: ")
                    .append(request.getTreatmentStartDate())
                    .append("\n");
            return;
        }

        if (request.getTreatmentStartDateType() == TreatmentStartDateType.YEAR_MONTH) {
            builder.append("- 치료 시작 시점: ")
                    .append(request.getTreatmentStartYear())
                    .append("년 ")
                    .append(request.getTreatmentStartMonth())
                    .append("월\n");
        }
    }

    private String generateAiAnswer(
            ChatSession chatSession,
            QuestionType questionType,
            ChatMessageRequest request
    ) {
        return switch (questionType) {
            case CHIP_CLAIM -> generateClaimAnswer();
            case CHIP_AMOUNT -> generateAmountAnswer();
            case CHIP_DOCUMENTS -> generateDocumentsAnswer();
            case CHIP_OVERVIEW -> generateOverviewAnswer(chatSession.getAnalysisId());
            case FREE_TEXT -> generateFreeTextAnswer();
        };
    }

    private String generateClaimAnswer() {
        return """
                입력하신 사고 및 치료 정보를 기준으로 보험금 청구 가능 여부를 확인해볼 수 있습니다.
                
                현재 단계에서는 증권 분석 결과와 사용자 입력 조건을 저장하는 1차 구현 상태입니다.
                이후 약관 RAG가 연결되면 해당 특약의 지급 사유, 면책 조건, 보장 기간을 함께 확인해 청구 가능성을 판단할 예정입니다.
                """;
    }

    private String generateAmountAnswer() {
        return """
                예상 보험금은 가입한 특약, 보장 한도, 입원 일수, 수술 종류, 진단 조건에 따라 달라질 수 있습니다.
                
                현재는 입력하신 치료 정보와 증권의 보장 카드 정보를 바탕으로 예상 금액 산정 로직을 연결하기 전 단계입니다.
                이후 coverage_item의 detail 정보와 약관 근거를 함께 사용해 예상 보험금을 계산할 예정입니다.
                """;
    }

    private String generateDocumentsAnswer() {
        return """
                일반적으로 보험금 청구에는 진단서, 진료비 영수증, 진료비 세부내역서, 입퇴원확인서, 수술확인서 등이 필요할 수 있습니다.
                
                다만 필요한 서류는 치료 유형과 보험사 기준에 따라 달라질 수 있으므로, 이후 약관 및 보험사별 청구 기준과 연결해 안내할 예정입니다.
                """;
    }

    private String generateOverviewAnswer(Long analysisId) {
        List<CoverageItemQueryRepository.CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        if (coverageItems.isEmpty()) {
            return "현재 증권 분석 결과에서 조회 가능한 보장 카드가 없습니다.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("현재 증권에서 확인된 보장 카드 목록입니다.\n\n");

        for (CoverageItemQueryRepository.CoverageItemInfo item : coverageItems) {
            builder.append("- ")
                    .append(item.coverageType())
                    .append(" / 탐지 여부: ")
                    .append(item.isDetected())
                    .append("\n");

            if (item.detail() != null && !item.detail().isBlank()) {
                builder.append("  세부 정보: ")
                        .append(item.detail())
                        .append("\n");
            }
        }

        return builder.toString().trim();
    }

    private String generateFreeTextAnswer() {
        return """
                입력하신 질문을 확인했습니다.
                
                현재는 자유 질문에 대해 실제 약관 근거를 검색하기 전 단계입니다.
                이후 RAG 검색이 연결되면 관련 약관 조항과 증권 분석 결과를 함께 참고해 답변할 예정입니다.
                """;
    }
}