package com.codit.be_boda.chat.validator;

import com.codit.be_boda.chat.dto.request.CastInfoRequest;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.DentalInfoRequest;
import com.codit.be_boda.chat.dto.request.HospitalizationInfoRequest;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.TreatmentStartDateType;
import com.codit.be_boda.chat.type.TreatmentType;
import com.codit.be_boda.global.exception.BusinessException;
import com.codit.be_boda.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class ChatMessageRequestValidator {

    public void validate(ChatMessageRequest request) {
        if (request == null) {
            throw invalidRequest("요청 본문은 필수입니다.");
        }

        QuestionType questionType =
                resolveQuestionType(request);

        if (questionType == null) {
            throw invalidRequest(
                    "questionType 또는 직접 입력할 message가 필요합니다."
            );
        }

        switch (questionType) {
            case FREE_TEXT ->
                    validateFreeText(request);

            case CHIP_OVERVIEW ->
                    validateChipOverview(request);

            case CHIP_CLAIM, CHIP_AMOUNT ->
                    validateInsuranceCondition(request);

            case CHIP_DOCUMENTS ->
                    validateChipDocuments(request);

            default ->
                    throw invalidRequest(
                            "지원하지 않는 questionType입니다."
                    );
        }
    }

    private void validateChipDocuments(
            ChatMessageRequest request
    ) {
        // 칩3은 questionType만으로도 기본 필요서류 안내 가능
        // 설문값이 전달되면 치료 유형별 서류를 추가로 반환
    }

    private void validateFreeText(ChatMessageRequest request) {
        if (isBlank(request.getMessage())) {
            throw invalidRequest("직접 입력 질문은 message가 필수입니다.");
        }
    }

    private void validateChipOverview(ChatMessageRequest request) {
        // 조건값 없이 가능
    }

    private void validateInsuranceCondition(ChatMessageRequest request) {
        if (request.getIncidentType() == null) {
            throw invalidRequest("보험 판단 질문은 incidentType이 필수입니다.");
        }

        if (request.getTreatmentTypes() == null || request.getTreatmentTypes().isEmpty()) {
            throw invalidRequest("보험 판단 질문은 treatmentTypes가 최소 1개 이상 필요합니다.");
        }

        validateConditionalTreatmentInfo(request);
        validateTreatmentStartDate(request);
    }

    private void validateConditionalTreatmentInfo(ChatMessageRequest request) {
        List<TreatmentType> treatmentTypes = request.getTreatmentTypes();

        if (treatmentTypes.contains(TreatmentType.DIAGNOSIS_ONLY)) {
            validateDiagnosisMessage(request);
        }

        if (treatmentTypes.contains(TreatmentType.DISABILITY)) {
            validateDisabilityMessage(request);
        }

        if (treatmentTypes.contains(TreatmentType.HOSPITALIZATION)) {
            validateHospitalizationInfo(request.getHospitalizationInfo());
        }

        if (treatmentTypes.contains(TreatmentType.CAST)) {
            validateCastInfo(request.getCastInfo());
        }

        if (treatmentTypes.contains(TreatmentType.DENTAL)) {
            validateDentalInfo(request.getDentalInfo());
        }
    }

    private void validateDiagnosisMessage(ChatMessageRequest request) {
        if (isBlank(request.getMessage())) {
            throw invalidRequest("DIAGNOSIS_ONLY 선택 시 진단명을 message에 입력해야 합니다.");
        }
    }

    private void validateDisabilityMessage(ChatMessageRequest request) {
        if (isBlank(request.getMessage())) {
            throw invalidRequest("DISABILITY 선택 시 장해 또는 후유장해 내용을 message에 입력해야 합니다.");
        }
    }

    private void validateHospitalizationInfo(HospitalizationInfoRequest info) {
        if (info == null) {
            throw invalidRequest("HOSPITALIZATION 선택 시 hospitalizationInfo가 필수입니다.");
        }

        if (info.getHospitalType() == null) {
            throw invalidRequest("입원 정보의 hospitalType은 필수입니다.");
        }

        if (info.getRoomType() == null) {
            throw invalidRequest("입원 정보의 roomType은 필수입니다.");
        }

        if (info.getHospitalizedNights() == null) {
            throw invalidRequest("입원 정보의 hospitalizedNights는 필수입니다.");
        }

        if (info.getHospitalizedNights() < 0) {
            throw invalidRequest("hospitalizedNights는 0 이상이어야 합니다.");
        }
    }

    private void validateCastInfo(CastInfoRequest info) {
        if (info == null) {
            throw invalidRequest("CAST 선택 시 castInfo가 필수입니다.");
        }

        if (info.getCastInjuryPartType() == null) {
            throw invalidRequest("깁스 정보의 castInjuryPartType은 필수입니다.");
        }

        if (info.getCastType() == null) {
            throw invalidRequest("깁스 정보의 castType은 필수입니다.");
        }
    }

    private void validateDentalInfo(DentalInfoRequest info) {
        if (info == null) {
            throw invalidRequest("DENTAL 선택 시 dentalInfo가 필수입니다.");
        }

        if (info.getDentalTreatmentTypes() == null || info.getDentalTreatmentTypes().isEmpty()) {
            throw invalidRequest("치과 치료 정보의 dentalTreatmentTypes는 최소 1개 이상 필요합니다.");
        }
    }

    private void validateTreatmentStartDate(ChatMessageRequest request) {
        if (request.getTreatmentStartDateType() == null) {
            throw invalidRequest("치료 시작일 유형은 필수입니다.");
        }

        TreatmentStartDateType type = request.getTreatmentStartDateType();

        if (type == TreatmentStartDateType.EXACT_DATE) {
            LocalDate treatmentStartDate = request.getTreatmentStartDate();

            if (treatmentStartDate == null) {
                throw invalidRequest("EXACT_DATE 선택 시 treatmentStartDate가 필수입니다.");
            }
        }

        if (type == TreatmentStartDateType.YEAR_MONTH) {
            Integer year = request.getTreatmentStartYear();
            Integer month = request.getTreatmentStartMonth();

            if (year == null) {
                throw invalidRequest("YEAR_MONTH 선택 시 treatmentStartYear가 필수입니다.");
            }

            if (month == null) {
                throw invalidRequest("YEAR_MONTH 선택 시 treatmentStartMonth가 필수입니다.");
            }

            if (month < 1 || month > 12) {
                throw invalidRequest("treatmentStartMonth는 1부터 12 사이여야 합니다.");
            }
        }
    }

    private QuestionType resolveQuestionType(
            ChatMessageRequest request
    ) {
        if (request.getQuestionType() != null) {
            return request.getQuestionType();
        }

        if (!isBlank(request.getMessage())) {
            return QuestionType.FREE_TEXT;
        }

        return null;
    }

    private BusinessException invalidRequest(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}