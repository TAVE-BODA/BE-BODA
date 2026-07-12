package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.HospitalizationInfoRequest;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.codit.be_boda.chat.type.HospitalType;
import com.codit.be_boda.chat.type.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalizationAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    // CHIP_CLAIM 중 HOSPITALIZATION 처리
    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        HospitalizationInfoRequest hospitalizationInfo = request.getHospitalizationInfo();

        if (hospitalizationInfo == null) {
            return "입원비 보장 확인을 위해 병원 종류, 병실 종류, 입원 기간 정보가 필요해요.";
        }

        CoverageItemDto hospitalizationItem = findMatchedHospitalizationItem(analysisId, hospitalizationInfo);

        if (hospitalizationItem == null) {
            return "입력하신 입원 조건과 직접 매칭되는 입원비 보장 항목을 찾지 못했어요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("청구 가능성이 있어요.\n\n")
                .append("가입하신 증권에서 ")
                .append(hospitalizationItem.coverageName())
                .append(" 보장이 확인돼요.\n")
                .append("입력하신 병원/병실 조건이 해당 보장 조건과 일치하면 청구 가능성이 있습니다.\n\n")
                .append("[확인된 보장]\n")
                .append("- ")
                .append(hospitalizationItem.coverageName());

        CoverageAmountDto amount = getFirstAmount(hospitalizationItem);

        if (amount != null && amount.coverageAmount() != null) {
            answer.append(": ")
                    .append(amount.condition())
                    .append(" ")
                    .append(String.format("%,d원", amount.coverageAmount()));
        }

        answer.append("\n");

        return answer.toString();
    }

    // CHIP_AMOUNT 중 HOSPITALIZATION 처리
    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        HospitalizationInfoRequest hospitalizationInfo = request.getHospitalizationInfo();

        if (hospitalizationInfo == null) {
            return "입원비 계산을 위해 병원 종류, 병실 종류, 입원 기간 정보가 필요해요.";
        }

        CoverageItemDto hospitalizationItem = findMatchedHospitalizationItem(analysisId, hospitalizationInfo);

        if (hospitalizationItem == null) {
            return "입력하신 입원 조건과 직접 매칭되는 입원비 보장 항목을 찾지 못했어요.";
        }

        CoverageAmountDto amount = getFirstAmount(hospitalizationItem);

        if (amount == null || amount.coverageAmount() == null) {
            return "입원비 보장은 확인됐지만, 정확한 금액은 약관 확인이 필요해요.";
        }

        int hospitalizedDays = calculateHospitalizedDays(hospitalizationInfo);
        long totalAmount = amount.coverageAmount() * hospitalizedDays;

        StringBuilder answer = new StringBuilder();

        answer.append("예상 입원비 보험금은 약 ")
                .append(String.format("%,d원", totalAmount))
                .append("이에요.\n\n")
                .append("가입하신 보험의 ")
                .append(hospitalizationItem.coverageName())
                .append(" 보장금액은 ")
                .append(amount.condition())
                .append(" ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("이에요.\n\n")
                .append("[계산 내역]\n")
                .append("- 입원 기간: ")
                .append(hospitalizationInfo.getHospitalizedNights())
                .append("박 ")
                .append(hospitalizedDays)
                .append("일\n")
                .append("- 1일당 보장금액: ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("\n")
                .append("- 예상 보험금: ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append(" × ")
                .append(hospitalizedDays)
                .append("일 = ")
                .append(String.format("%,d원", totalAmount))
                .append("\n");

        return answer.toString();
    }

    private CoverageItemDto findMatchedHospitalizationItem(
            Long analysisId,
            HospitalizationInfoRequest hospitalizationInfo
    ) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"입원".equals(coverageItem.coverageType())
                    && !"입원비".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                return null;
            }

            return detail.items().stream()
                    .filter(item -> isMatchedHospitalizationItem(item, hospitalizationInfo))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    private boolean isMatchedHospitalizationItem(
            CoverageItemDto item,
            HospitalizationInfoRequest hospitalizationInfo
    ) {
        if (item.coverageName() == null) {
            return false;
        }

        String coverageName = normalize(item.coverageName());

        RoomType roomType = hospitalizationInfo.getRoomType();
        HospitalType hospitalType = hospitalizationInfo.getHospitalType();

        if (roomType == RoomType.TWO_THREE_ROOM) {
            return coverageName.contains("2")
                    || coverageName.contains("3인실")
                    || coverageName.contains("2·3인실")
                    || coverageName.contains("2,3인실");
        }

        if (roomType == RoomType.PRIVATE_ROOM) {
            return hospitalType == HospitalType.TERTIARY_HOSPITAL
                    && coverageName.contains("상급")
                    && coverageName.contains("1인실");
        }

        if (roomType == RoomType.GENERAL_ROOM) {
            return coverageName.contains("일반병실")
                    || coverageName.contains("4인실")
                    || coverageName.contains("입원일당");
        }

        return false;
    }

    private int calculateHospitalizedDays(HospitalizationInfoRequest hospitalizationInfo) {
        if (hospitalizationInfo.getHospitalizedNights() == null
                || hospitalizationInfo.getHospitalizedNights() < 0) {
            return 0;
        }

        return hospitalizationInfo.getHospitalizedNights() + 1;
    }

    private CoverageAmountDto getFirstAmount(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return null;
        }

        return item.amounts().get(0);
    }

    private String normalize(String value) {
        return value.replace(" ", "");
    }

    private CoverageLlmResponse parseCoverageDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return new CoverageLlmResponse(false, List.of(), null);
        }

        try {
            return objectMapper.readValue(detail, CoverageLlmResponse.class);
        } catch (Exception e) {
            log.warn("coverage_item detail 파싱 실패. detail={}", detail, e);
            return new CoverageLlmResponse(false, List.of(), null);
        }
    }
}