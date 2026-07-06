package com.codit.be_boda.chat.service;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.service.answer.CastAnswerGenerator;
import com.codit.be_boda.chat.service.answer.SurgeryAnswerGenerator;
import com.codit.be_boda.chat.service.answer.HospitalizationAnswerGenerator;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatAnswerService {

    private final CastAnswerGenerator castAnswerGenerator; // 골절
    private final SurgeryAnswerGenerator surgeryAnswerGenerator; // 수술
    private final HospitalizationAnswerGenerator hospitalizationAnswerGenerator; // 입원

    // ChatService에서 호출하는 메인 메서드
    public String generateAnswer(ChatSession chatSession, ChatMessageRequest request) {
        if (request.getQuestionType() == QuestionType.CHIP_CLAIM) {
            return generateClaimAnswer(chatSession.getAnalysisId(), request);
        }

        if (request.getQuestionType() == QuestionType.CHIP_AMOUNT) {
            return generateAmountAnswer(chatSession.getAnalysisId(), request);
        }

        if (request.getQuestionType() == QuestionType.CHIP_DOCUMENTS) {
            return "필요 서류 안내는 약관 근거 확인이 필요해서 이후 단계에서 제공될 예정입니다.";
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


        return "입력하신 치료 항목에 대해 청구 가능 여부를 확인하려면 추가 보장 항목 매칭이 필요합니다.";
    }

    // CHIP_AMOUNT 처리
    private String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        if (hasTreatmentType(request, TreatmentType.CAST)) {
            return castAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateClaimAnswer(analysisId, request);
        }

        if (hasTreatmentType(request, TreatmentType.HOSPITALIZATION)) {
            return hospitalizationAnswerGenerator.generateAmountAnswer(analysisId, request);
        }

        return "입력하신 치료 항목에 대한 예상 보험금 계산은 아직 준비 중입니다.";
    }

    // 요청에 특정 치료 유형이 포함되어 있는지 확인
    private boolean hasTreatmentType(ChatMessageRequest request, TreatmentType treatmentType) {
        return request.getTreatmentTypes() != null
                && request.getTreatmentTypes().contains(treatmentType);
    }
}