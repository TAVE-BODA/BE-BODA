package com.codit.be_boda.chat.service;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.service.answer.ClaimAnswerResult;
import com.codit.be_boda.chat.service.answer.CastAnswerGenerator;
import com.codit.be_boda.chat.service.answer.SurgeryAnswerGenerator;
import com.codit.be_boda.chat.service.answer.HospitalizationAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DentalAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DiagnosisAnswerGenerator;
import com.codit.be_boda.chat.service.answer.OutpatientAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DisabilityAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DocumentAnswerGenerator;
import com.codit.be_boda.chat.service.answer.DocumentAnswerResult;
import com.codit.be_boda.chat.service.answer.AmountAnswerResult;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatAnswerService {

    private final CastAnswerGenerator castAnswerGenerator; // 골절
    private final SurgeryAnswerGenerator surgeryAnswerGenerator; // 수술
    private final HospitalizationAnswerGenerator hospitalizationAnswerGenerator; // 입원
    private final DentalAnswerGenerator dentalAnswerGenerator; // 치아
    private final DiagnosisAnswerGenerator diagnosisAnswerGenerator; // 진단
    private final OutpatientAnswerGenerator outpatientAnswerGenerator; // 통원/외래
    private final DisabilityAnswerGenerator disabilityAnswerGenerator; // 장해/후유장해
    private final DocumentAnswerGenerator documentAnswerGenerator; // 필요서류

    // ChatService에서 호출하는 메인 메서드
    public String generateAnswer(ChatSession chatSession, ChatMessageRequest request) {
        return generateAnswerResult(chatSession, request).messageContent();
    }

    public ChatAnswerResult generateAnswerResult(ChatSession chatSession, ChatMessageRequest request) {
        if (request.getQuestionType() == QuestionType.CHIP_CLAIM) {
            ClaimAnswerResult result = generateClaimAnswerResult(chatSession.getAnalysisId(), request);

            return ChatAnswerResult.claim(
                    result.messageContent(),
                    result.claimGuide()
            );
        }

        if (request.getQuestionType() == QuestionType.CHIP_AMOUNT) {
            AmountAnswerResult result = generateAmountAnswerResult(chatSession.getAnalysisId(), request);

            return ChatAnswerResult.amount(
                    result.messageContent(),
                    result.amountGuide()
            );
        }

        if (request.getQuestionType() == QuestionType.CHIP_DOCUMENTS) {
            DocumentAnswerResult result =
                    documentAnswerGenerator.generateStructuredAnswer(chatSession.getTermsDocumentId(), request);

            return ChatAnswerResult.documents(
                    result.messageContent(),
                    result.documentGuide(),
                    result.hasSources()
            );
        }

        if (request.getQuestionType() == QuestionType.CHIP_OVERVIEW) {
            return ChatAnswerResult.text("가입하신 증권의 보장 항목은 보장 카드에서 확인할 수 있어요.");
        }

        if (request.getQuestionType() == QuestionType.FREE_TEXT) {
            return ChatAnswerResult.text("직접 입력 질문은 이후 약관 기반 답변 기능에서 처리될 예정입니다.");
        }

        return ChatAnswerResult.text("요청하신 내용을 확인했어요.");
    }

    // 청구 가능 여부 카드 DTO 생성
    private ClaimGuideResponse buildClaimGuide(ChatMessageRequest request) {
        return ClaimGuideResponse.builder()
                .claimStatus("NEEDS_REVIEW")
                .summary("입력하신 조건 기준으로 청구 가능성 확인이 필요해요.")
                .reasons(List.of(
                        "입력하신 사고 및 치료 정보를 기준으로 보장 항목 매칭이 필요합니다.",
                        "현재는 증권 분석 결과와 사용자 조건을 함께 확인하는 단계입니다."
                ))
                .cautions(List.of(
                        "실제 지급 여부는 보험사 심사 결과와 약관 조건에 따라 달라질 수 있어요."
                ))
                .build();
    }

    // 예상 보험금 카드 DTO 생성
    private AmountGuideResponse buildAmountGuide(ChatMessageRequest request) {
        return AmountGuideResponse.builder()
                .calculationAvailable(true)
                .estimatedItems(List.of(
                        AmountGuideResponse.EstimatedItem.builder()
                                .coverageName("보장 후보")
                                .amountText("가입금액 또는 약관 기준 확인 필요")
                                .reason("입력하신 치료 유형과 매칭되는 보장 항목 확인이 필요합니다.")
                                .build()
                ))
                .cautions(List.of(
                        "정확한 금액은 가입 특약, 한도, 감액 조건에 따라 달라질 수 있어요."
                ))
                .build();
    }

    // 치료 유형별 청구 가능 여부 구조화 응답 생성
    private ClaimAnswerResult generateClaimAnswerResult(Long analysisId, ChatMessageRequest request) {
        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateStructuredClaimAnswer(analysisId, request);
        }

        String messageContent = generateClaimAnswer(analysisId, request);
        ClaimGuideResponse claimGuide = buildClaimGuide(request);

        return new ClaimAnswerResult(messageContent, claimGuide);
    }

    // 치료 유형별 예상 보험금 구조화 응답 생성
    private AmountAnswerResult generateAmountAnswerResult(Long analysisId, ChatMessageRequest request) {
        if (hasTreatmentType(request, TreatmentType.SURGERY)) {
            return surgeryAnswerGenerator.generateStructuredAmountAnswer(analysisId, request);
        }

        String messageContent = generateAmountAnswer(analysisId, request);
        AmountGuideResponse amountGuide = buildAmountGuide(request);

        return new AmountAnswerResult(messageContent, amountGuide);
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