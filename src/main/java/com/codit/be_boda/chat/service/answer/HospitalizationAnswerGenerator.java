package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.HospitalizationInfoRequest;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.codit.be_boda.chat.repository.PolicyAnalysisQueryRepository;
import com.codit.be_boda.chat.type.HospitalType;
import com.codit.be_boda.chat.type.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalizationAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;
    private final PolicyAnalysisQueryRepository policyAnalysisQueryRepository;

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

        CoverageAmountDto amount = findApplicableHospitalizationAmount(
                analysisId,
                request,
                hospitalizationItem
        );

        if (amount != null && amount.coverageAmount() != null) {
            answer.append(": ")
                    .append(amount.condition())
                    .append(" ")
                    .append(String.format("%,d원", amount.coverageAmount()));
        }

        answer.append("\n");

        return answer.toString();
    }

    // CHIP_CLAIM 중 HOSPITALIZATION 문자열 응답과 DTO 카드 데이터 생성
    public ClaimAnswerResult generateStructuredClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent = generateClaimAnswer(analysisId, request);
        HospitalizationInfoRequest hospitalizationInfo = request.getHospitalizationInfo();

        // 입원 정보가 없는 경우
        if (hospitalizationInfo == null) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NEEDS_REVIEW")
                    .summary("입원 조건을 확인하기 위한 정보가 더 필요해요.")
                    .reasons(List.of(
                            "병원 종류, 병실 종류, 입원 기간 정보가 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "입원 정보를 입력하면 가입한 입원 보장과 비교할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        CoverageItemDto hospitalizationItem =
                findMatchedHospitalizationItem(analysisId, hospitalizationInfo);

        // 매칭되는 입원 보장이 없는 경우
        if (hospitalizationItem == null) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NOT_AVAILABLE")
                    .summary("입력하신 입원 조건과 매칭되는 보장 항목을 찾지 못했어요.")
                    .reasons(List.of(
                            "가입하신 증권에서 병원 및 병실 조건과 일치하는 입원 보장이 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "다른 입원 특약이나 약관 조건에 따라 추가 확인이 필요할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<String> reasons = new ArrayList<>();

        reasons.add(
                "가입하신 증권의 "
                        + hospitalizationItem.coverageName()
                        + " 조건에 해당해 청구 가능성이 있어요."
        );

        CoverageAmountDto amount = findApplicableHospitalizationAmount(
                analysisId,
                request,
                hospitalizationItem
        );

        if (amount != null && amount.coverageAmount() != null) {
            reasons.add(
                    "입원 보장금액은 "
                            + amount.condition()
                            + " "
                            + String.format("%,d원", amount.coverageAmount())
                            + "으로 확인돼요."
            );
        }

        ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                .claimStatus("POSSIBLE")
                .summary("입원비 청구 가능성이 있어요.")
                .reasons(reasons)
                .cautions(List.of(
                        "실제 병원 및 병실 분류가 약관상 지급 조건과 일치해야 해요.",
                        "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                ))
                .build();

        return new ClaimAnswerResult(messageContent, claimGuide);
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

        CoverageAmountDto amount = findApplicableHospitalizationAmount(
                analysisId,
                request,
                hospitalizationItem
        );

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

    // CHIP_AMOUNT 중 HOSPITALIZATION 문자열 응답과 카드 데이터 생성
    public AmountAnswerResult generateStructuredAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent =
                generateAmountAnswer(analysisId, request);

        HospitalizationInfoRequest hospitalizationInfo =
                request.getHospitalizationInfo();

        // 입원 정보가 없는 경우
        if (hospitalizationInfo == null) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "예상 입원비 계산을 위해 병원 종류, 병실 종류, 입원 기간 정보가 필요해요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        CoverageItemDto hospitalizationItem =
                findMatchedHospitalizationItem(
                        analysisId,
                        hospitalizationInfo
                );

        // 조건과 매칭되는 입원 보장이 없는 경우
        if (hospitalizationItem == null) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "입력하신 병원 및 병실 조건과 직접 매칭되는 입원비 보장 항목을 찾지 못했어요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        CoverageAmountDto amount =
                findApplicableHospitalizationAmount(
                        analysisId,
                        request,
                        hospitalizationItem
                );

        // 보장은 있지만 금액을 확인하지 못한 경우
        if (amount == null
                || amount.coverageAmount() == null) {

            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "입원비 보장은 확인됐지만 정확한 보장금액은 약관 확인이 필요해요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        int hospitalizedDays =
                calculateHospitalizedDays(
                        hospitalizationInfo
                );

        if (hospitalizedDays <= 0) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "예상 입원비 계산을 위해 올바른 입원 기간이 필요해요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        long totalAmount =
                amount.coverageAmount() * hospitalizedDays;

        AmountGuideResponse amountGuide =
                AmountGuideResponse.builder()
                        .calculationAvailable(true)
                        .estimatedItems(List.of(
                                AmountGuideResponse.EstimatedItem.builder()
                                        .coverageName(
                                                hospitalizationItem.coverageName()
                                        )
                                        .amountText(
                                                String.format(
                                                        "%,d원",
                                                        totalAmount
                                                )
                                        )
                                        .reason(
                                                String.format(
                                                        "1일당 %,d원 × %d일로 계산한 예상 금액이에요.",
                                                        amount.coverageAmount(),
                                                        hospitalizedDays
                                                )
                                        )
                                        .build()
                        ))
                        .cautions(List.of(
                                "실제 병원 및 병실 분류가 약관상 지급 조건과 일치해야 해요.",
                                "입원 인정 일수와 지급 한도에 따라 실제 보험금이 달라질 수 있어요.",
                                "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                        ))
                        .build();

        return new AmountAnswerResult(
                messageContent,
                amountGuide
        );
    }

    private CoverageItemDto findMatchedHospitalizationItem(
            Long analysisId,
            HospitalizationInfoRequest hospitalizationInfo
    ) {
        if (analysisId == null) {
            return null;
        }

        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"입원".equals(coverageItem.coverageType())
                    && !"입원비".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail =
                    parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            CoverageItemDto matchedItem = detail.items().stream()
                    .filter(item ->
                            isMatchedHospitalizationItem(
                                    item,
                                    hospitalizationInfo
                            )
                    )
                    .findFirst()
                    .orElse(null);

            if (matchedItem != null) {
                return matchedItem;
            }
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
            return coverageName.contains("2인실")
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

    private CoverageAmountDto findApplicableHospitalizationAmount(
            Long analysisId,
            ChatMessageRequest request,
            CoverageItemDto hospitalizationItem
    ) {
        if (hospitalizationItem == null
                || hospitalizationItem.amounts() == null
                || hospitalizationItem.amounts().isEmpty()) {
            return null;
        }

        if (hospitalizationItem.amounts().size() == 1) {
            return hospitalizationItem.amounts().get(0);
        }

        if (analysisId == null
                || request == null
                || request.getTreatmentStartDate() == null) {
            return null;
        }

        LocalDate insuranceStartDate =
                policyAnalysisQueryRepository
                        .findInsuranceStartDateByAnalysisId(analysisId)
                        .orElse(null);

        if (insuranceStartDate == null) {
            return null;
        }

        LocalDate treatmentStartDate =
                request.getTreatmentStartDate();

        if (treatmentStartDate.isBefore(insuranceStartDate)) {
            return null;
        }

        boolean withinOneYear =
                !treatmentStartDate.isAfter(
                        insuranceStartDate.plusYears(1)
                );

        String targetCondition =
                withinOneYear
                        ? "1년이내"
                        : "1년초과";

        return hospitalizationItem.amounts()
                .stream()
                .filter(amount ->
                        normalize(amount.condition())
                                .contains(targetCondition)
                )
                .findFirst()
                .orElse(null);
    }

    private String getHospitalDescription(HospitalType hospitalType) {
        if (hospitalType == null) {
            return "병원 종류 미확인";
        }

        return switch (hospitalType) {
            case LOCAL_CLINIC -> "동네 병원·의원";
            case GENERAL_HOSPITAL -> "종합병원";
            case TERTIARY_HOSPITAL -> "상급종합병원";
        };
    }

    private String getRoomDescription(RoomType roomType) {
        if (roomType == null) {
            return "병실 종류 미확인";
        }

        return switch (roomType) {
            case PRIVATE_ROOM -> "1인실";
            case TWO_THREE_ROOM -> "2·3인실";
            case GENERAL_ROOM -> "일반병실";
        };
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("\\s+", "");
    }

    private CoverageLlmResponse parseCoverageDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return new CoverageLlmResponse(false, List.of(), null);
        }

        try {
            return objectMapper.readValue(
                    detail,
                    CoverageLlmResponse.class
            );
        } catch (Exception e) {
            log.warn(
                    "coverage_item detail 파싱 실패. detail={}",
                    detail,
                    e
            );

            return new CoverageLlmResponse(false, List.of(), null);
        }
    }
}