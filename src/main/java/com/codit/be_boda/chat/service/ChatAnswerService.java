package com.codit.be_boda.chat.service;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.service.answer.CastAnswerGenerator;
import com.codit.be_boda.chat.service.answer.SurgeryAnswerGenerator;
import com.codit.be_boda.chat.service.answer.HospitalizationAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DentalAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DiagnosisAnswerGenerator;
import com.codit.be_boda.chat.service.answer.OutpatientAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DisabilityAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DocumentAnswerGenerator;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatAnswerService {

    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final CastAnswerGenerator castAnswerGenerator;
    private final SurgeryAnswerGenerator surgeryAnswerGenerator;
    private final HospitalizationAnswerGenerator hospitalizationAnswerGenerator;
    private final DentalAnswerGenerator dentalAnswerGenerator;
    private final DiagnosisAnswerGenerator diagnosisAnswerGenerator;
    private final OutpatientAnswerGenerator outpatientAnswerGenerator;
    private final DisabilityAnswerGenerator disabilityAnswerGenerator;
    private final DocumentAnswerGenerator documentAnswerGenerator;

    public String generateAnswer(ChatSession chatSession, ChatMessageRequest request) {
        // 채팅방에 연결된 증권 ID 목록 조회
        List<Long> analysisIds = chatSessionPolicyRepository
                .findByChatSessionId(chatSession.getChatSessionId())
                .stream()
                .map(ChatSessionPolicy::getAnalysisId)
                .toList();

        // 증권이 여러 개면 첫 번째 기준으로 처리
        Long analysisId = analysisIds.isEmpty() ? null : analysisIds.get(0);

        if (request.getQuestionType() == QuestionType.CHIP_CLAIM) {
            return generateClaimAnswer(analysisId, request);
        }

        if (request.getQuestionType() == QuestionType.CHIP_AMOUNT) {
            return generateAmountAnswer(analysisId, request);
        }

        if (request.getQuestionType() == QuestionType.CHIP_DOCUMENTS) {
            return documentAnswerGenerator.generateAnswer(chatSession.getTermsDocumentId(), request);
        }

        if (request.getQuestionType() == QuestionType.CHIP_OVERVIEW) {
            return "가입하신 증권의 보장 항목은 보장 카드에서 확인할 수 있어요.";
        }

        if (request.getQuestionType() == QuestionType.FREE_TEXT) {
            return "직접 입력 질문은 이후 약관 기반 답변 기능에서 처리될 예정입니다.";
        }

        return "요청하신 내용을 확인했어요.";
    }

    // CHIP_CLAIM 처리
    private String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        if (hasTreatmentType(request, TreatmentType.CAST)) {
            return castAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.HOSPITALIZATION)) {
            return hospitalizationAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DENTAL)) {
            return dentalAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DIAGNOSIS_ONLY)) {
            return diagnosisAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.OUTPATIENT)) {
            return outpatientAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DISABILITY)) {
            return disabilityAnswerGenerator.generateClaimAnswer(analysisId, request);
        }


        return "입력하신 치료 항목에 대해 청구 가능 여부를 확인하려면 추가 보장 항목 매칭이 필요합니다.";
    }

    // CHIP_AMOUNT 처리
    private String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        if (hasTreatmentType(request, TreatmentType.CAST)) {
            return castAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.HOSPITALIZATION)) {
            return hospitalizationAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DENTAL)) {
            return dentalAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DIAGNOSIS_ONLY)) {
            return diagnosisAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.OUTPATIENT)) {
            return outpatientAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.DISABILITY)) {
            return disabilityAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        return "입력하신 치료 항목에 대한 예상 보험금 계산은 아직 준비 중입니다.";
    }

    // 요청에 특정 치료 유형이 포함되어 있는지 확인
    private boolean hasTreatmentType(ChatMessageRequest request, TreatmentType treatmentType) {
        return request.getTreatmentTypes() != null
                && request.getTreatmentTypes().contains(treatmentType);
    }
}