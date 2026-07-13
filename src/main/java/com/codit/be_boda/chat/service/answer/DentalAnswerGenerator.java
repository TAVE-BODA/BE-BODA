package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.request.DentalInfoRequest;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.codit.be_boda.chat.type.DentalTreatmentCountType;
import com.codit.be_boda.chat.type.DentalTreatmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DentalAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    // CHIP_CLAIM 중 DENTAL 처리
    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        DentalInfoRequest dentalInfo = request.getDentalInfo();

        if (dentalInfo == null
                || dentalInfo.getDentalTreatmentTypes() == null
                || dentalInfo.getDentalTreatmentTypes().isEmpty()) {
            return "치아치료 보장 확인을 위해 어떤 치아 치료를 받았는지 선택해 주세요.";
        }

        List<CoverageItemDto> matchedItems = findMatchedDentalItems(analysisId, dentalInfo);

        if (matchedItems.isEmpty()) {
            return "입력하신 치아 치료와 직접 매칭되는 치아치료 보장 항목을 찾지 못했어요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("청구 가능성이 있는 치아치료 보장이 있어요.\n\n")
                .append("가입하신 증권에서 입력하신 치아 치료와 관련된 보장 항목이 확인돼요.\n")
                .append("다만 실제 지급 여부는 치료 종류, 치료 개수, 약관상 조건에 따라 달라질 수 있어요.\n\n")
                .append("[확인된 보장]\n");

        matchedItems.forEach(item -> {
            answer.append("- ")
                    .append(item.coverageName());

            String amountText = buildAmountTextWithoutTotal(item);
            if (!amountText.isBlank()) {
                answer.append(": ")
                        .append(amountText);
            }

            answer.append("\n");
        });

        return answer.toString();
    }

    // CHIP_CLAIM 중 DENTAL 문자열 응답과 DTO 카드 데이터 생성
    public ClaimAnswerResult generateStructuredClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent = generateClaimAnswer(analysisId, request);
        DentalInfoRequest dentalInfo = request.getDentalInfo();

        if (dentalInfo == null
                || dentalInfo.getDentalTreatmentTypes() == null
                || dentalInfo.getDentalTreatmentTypes().isEmpty()) {

            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NEEDS_REVIEW")
                    .summary("치아 치료 종류를 확인하기 위한 정보가 필요해요.")
                    .reasons(List.of(
                            "어떤 치아 치료를 받았는지 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "치아 치료 종류를 입력하면 가입한 치아 보장과 비교할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<CoverageItemDto> matchedItems =
                findMatchedDentalItems(analysisId, dentalInfo);

        if (matchedItems.isEmpty()) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NOT_AVAILABLE")
                    .summary("입력하신 치아 치료와 매칭되는 보장 항목을 찾지 못했어요.")
                    .reasons(List.of(
                            "가입하신 증권에서 선택한 치아 치료와 직접 매칭되는 보장이 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "다른 치아 특약이나 약관에 관련 보장이 있는지 추가 확인이 필요할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<String> reasons = new ArrayList<>();

        String selectedTreatments = String.join(
                ", ",
                dentalInfo.getDentalTreatmentTypes().stream()
                        .map(this::getDentalTreatmentDescription)
                        .toList()
        );

        reasons.add("입력하신 치아 치료 종류는 " + selectedTreatments + "예요.");

        for (CoverageItemDto item : matchedItems) {
            reasons.add(
                    "가입하신 증권에서 "
                            + item.coverageName()
                            + " 보장이 확인돼요."
            );

            String amountText = buildAmountTextWithoutTotal(item);

            if (!amountText.isBlank()) {
                reasons.add(
                        "확인된 보장금액은 "
                                + amountText
                                + "이에요."
                );
            }
        }

        if (dentalInfo.getDentalTreatmentCountType()
                == DentalTreatmentCountType.EXACT_COUNT
                && dentalInfo.getDentalTreatmentCount() != null) {

            reasons.add(
                    "입력하신 치료 치아 개수는 "
                            + dentalInfo.getDentalTreatmentCount()
                            + "개예요."
            );
        }

        List<String> cautions = new ArrayList<>();

        if (dentalInfo.getDentalTreatmentTypes()
                .contains(DentalTreatmentType.EXTRACTION)) {
            cautions.add("유치가 아닌 영구치 발치인지 확인이 필요해요.");
        }

        if (hasPeriodCondition(matchedItems)) {
            cautions.add("가입 후 경과 기간에 따라 지급금액이 달라질 수 있어요.");
        }        cautions.add("실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요.");

        ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                .claimStatus("POSSIBLE")
                .summary("입력하신 치아 치료와 매칭되는 보장이 확인돼 청구 가능성이 있어요.")
                .reasons(reasons)
                .cautions(cautions)
                .build();

        return new ClaimAnswerResult(messageContent, claimGuide);
    }

    // CHIP_AMOUNT 중 DENTAL 처리
    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        DentalInfoRequest dentalInfo = request.getDentalInfo();

        if (dentalInfo == null
                || dentalInfo.getDentalTreatmentTypes() == null
                || dentalInfo.getDentalTreatmentTypes().isEmpty()) {
            return "치아치료 예상 보험금 계산을 위해 어떤 치아 치료를 받았는지 선택해 주세요.";
        }

        List<CoverageItemDto> matchedItems = findMatchedDentalItems(analysisId, dentalInfo);

        if (matchedItems.isEmpty()) {
            return "입력하신 치아 치료와 직접 매칭되는 치아치료 보장 항목을 찾지 못했어요.";
        }

        StringBuilder answer = new StringBuilder();

        boolean canCalculateTotalAmount = canCalculateTotalAmount(dentalInfo);

        if (canCalculateTotalAmount) {
            Long totalAmount = calculateTotalAmount(matchedItems, dentalInfo);

            if (totalAmount != null) {
                answer.append("예상 치아치료 보험금은 약 ")
                        .append(String.format("%,d원", totalAmount))
                        .append("이에요.\n\n");
            } else {
                answer.append("치아치료 예상 보험금 후보를 확인했어요.\n\n");
            }

            answer.append("[계산 내역]\n");

            matchedItems.forEach(item -> {
                answer.append("- ")
                        .append(item.coverageName());

                String amountText = buildAmountText(item, dentalInfo);
                if (!amountText.isBlank()) {
                    answer.append(": ")
                            .append(amountText);
                } else {
                    answer.append(": 금액 확인 필요");
                }

                answer.append("\n");
            });

            appendCountNotice(answer, dentalInfo);

            return answer.toString();
        }

        answer.append("치료 종류 확인이 필요해요.\n\n")
                .append("선택하신 치아치료 항목에는 여러 치료가 포함될 수 있어요.\n")
                .append("치료 종류에 따라 금액이 달라 하나의 예상 보험금으로 합산하기 어렵습니다.\n\n")
                .append("[확인된 후보]\n");

        matchedItems.forEach(item -> {
            answer.append("- ")
                    .append(item.coverageName());

            String amountText = buildAmountTextWithoutTotal(item);
            if (!amountText.isBlank()) {
                answer.append(": ")
                        .append(amountText);
            } else {
                answer.append(": 금액 확인 필요");
            }

            answer.append("\n");
        });

        answer.append("\n정확한 총액 계산을 위해서는 실제 치료명이 필요해요.\n")
                .append("예: 임플란트, 브릿지, 크라운, 레진, 인레이 등\n");

        return answer.toString();
    }

    private List<CoverageItemDto> findMatchedDentalItems(
            Long analysisId,
            DentalInfoRequest dentalInfo
    ) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        List<CoverageItemDto> matchedItems = new ArrayList<>();

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"치아".equals(coverageItem.coverageType())
                    && !"치아치료".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            detail.items().stream()
                    .filter(item -> isMatchedDentalItem(item, dentalInfo))
                    .forEach(matchedItems::add);
        }

        return matchedItems;
    }

    private boolean isMatchedDentalItem(
            CoverageItemDto item,
            DentalInfoRequest dentalInfo
    ) {
        if (item.coverageName() == null) {
            return false;
        }

        String coverageName = normalize(item.coverageName());

        return dentalInfo.getDentalTreatmentTypes().stream()
                .anyMatch(type -> matchesDentalTreatmentType(coverageName, type));
    }

    private boolean matchesDentalTreatmentType(
            String coverageName,
            DentalTreatmentType dentalTreatmentType
    ) {
        if (dentalTreatmentType == DentalTreatmentType.EXTRACTION) {
            boolean isExtractionCoverage =
                    coverageName.contains("영구치발치")
                            || coverageName.contains("발치보험금")
                            || coverageName.contains("치아발거");

            boolean isProstheticCoverage =
                    coverageName.contains("브릿지")
                            || coverageName.contains("임플란트")
                            || coverageName.contains("고정성가공의치")
                            || coverageName.contains("보철");

            return isExtractionCoverage && !isProstheticCoverage;
        }

        if (dentalTreatmentType == DentalTreatmentType.CROWN_IMPLANT) {
            return coverageName.contains("임플란트")
                    || coverageName.contains("크라운")
                    || coverageName.contains("브릿지")
                    || coverageName.contains("고정성가공의치")
                    || coverageName.contains("틀니")
                    || coverageName.contains("보철");
        }

        if (dentalTreatmentType == DentalTreatmentType.FILLING) {
            return coverageName.contains("충전")
                    || coverageName.contains("레진")
                    || coverageName.contains("인레이")
                    || coverageName.contains("온레이")
                    || coverageName.contains("아말감");
        }

        if (dentalTreatmentType == DentalTreatmentType.ROOT_CANAL) {
            return coverageName.contains("신경치료")
                    || coverageName.contains("치수치료")
                    || coverageName.contains("근관치료");
        }

        return false;
    }

    // 총액 계산이 가능한 치아치료인지 판단
    private boolean canCalculateTotalAmount(DentalInfoRequest dentalInfo) {
        if (dentalInfo.getDentalTreatmentTypes() == null
                || dentalInfo.getDentalTreatmentTypes().isEmpty()) {
            return false;
        }

        // 여러 치료를 동시에 선택한 경우에는 치료별 개수를 알 수 없으므로 총액 계산하지 않음
        if (dentalInfo.getDentalTreatmentTypes().size() > 1) {
            return false;
        }

        DentalTreatmentType selectedType = dentalInfo.getDentalTreatmentTypes().get(0);

        // 발치, 신경치료는 비교적 보장 항목이 명확하므로 총액 계산
        return selectedType == DentalTreatmentType.EXTRACTION
                || selectedType == DentalTreatmentType.ROOT_CANAL;
    }

    private Long calculateTotalAmount(
            List<CoverageItemDto> matchedItems,
            DentalInfoRequest dentalInfo
    ) {
        int treatmentCount = resolveDentalTreatmentCount(dentalInfo);

        long totalAmount = 0L;
        boolean hasAmount = false;

        for (CoverageItemDto item : matchedItems) {
            if (item.amounts() == null || item.amounts().isEmpty()) {
                continue;
            }

            CoverageAmountDto amount = item.amounts().get(0);

            if (amount.coverageAmount() == null) {
                continue;
            }

            totalAmount += amount.coverageAmount() * treatmentCount;
            hasAmount = true;
        }

        if (!hasAmount) {
            return null;
        }

        return totalAmount;
    }

    // 총액 계산 가능한 케이스에서 개수까지 반영한 금액 문구 생성
    private String buildAmountText(CoverageItemDto item, DentalInfoRequest dentalInfo) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return "";
        }

        int treatmentCount = resolveDentalTreatmentCount(dentalInfo);

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < item.amounts().size(); i++) {
            CoverageAmountDto amount = item.amounts().get(i);

            if (i > 0) {
                builder.append(", ");
            }

            builder.append(amount.condition())
                    .append(" ");

            if (amount.coverageAmount() == null) {
                builder.append("약관 확인 필요");
                continue;
            }

            long totalAmount = amount.coverageAmount() * treatmentCount;

            if (treatmentCount > 1) {
                builder.append(String.format(
                        "%,d원 × %d개 = %,d원",
                        amount.coverageAmount(),
                        treatmentCount,
                        totalAmount
                ));
            } else {
                builder.append(String.format("%,d원", amount.coverageAmount()));
            }
        }

        return builder.toString();
    }

    private boolean hasPeriodCondition(List<CoverageItemDto> items) {
        for (CoverageItemDto item : items) {
            if (item.amounts() == null || item.amounts().isEmpty()) {
                continue;
            }

            for (CoverageAmountDto amount : item.amounts()) {
                String condition = amount.condition();

                if (condition == null || condition.isBlank()) {
                    continue;
                }

                if (condition.contains("이내")
                        || condition.contains("초과")
                        || condition.contains("가입")
                        || condition.contains("계약")) {
                    return true;
                }
            }
        }

        return false;
    }

    // 후보 안내 케이스에서 금액만 보여주는 문구 생성
    private String buildAmountTextWithoutTotal(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < item.amounts().size(); i++) {
            CoverageAmountDto amount = item.amounts().get(i);

            if (i > 0) {
                builder.append(", ");
            }

            String condition = amount.condition();

            if (condition != null
                    && !condition.isBlank()
                    && !"조건없음".equals(condition)) {
                builder.append(condition)
                        .append(" ");
            }

            if (amount.coverageAmount() == null) {
                builder.append("약관 확인 필요");
            } else {
                builder.append(String.format("%,d원", amount.coverageAmount()));
            }
        }

        return builder.toString();
    }

    private int resolveDentalTreatmentCount(DentalInfoRequest dentalInfo) {
        if (dentalInfo.getDentalTreatmentCountType() == DentalTreatmentCountType.EXACT_COUNT
                && dentalInfo.getDentalTreatmentCount() != null
                && dentalInfo.getDentalTreatmentCount() > 0) {
            return dentalInfo.getDentalTreatmentCount();
        }

        return 1;
    }

    private boolean isUnknownDentalTreatmentCount(DentalInfoRequest dentalInfo) {
        return dentalInfo.getDentalTreatmentCountType() == DentalTreatmentCountType.UNKNOWN;
    }

    private void appendCountNotice(StringBuilder answer, DentalInfoRequest dentalInfo) {
        if (isUnknownDentalTreatmentCount(dentalInfo)) {
            answer.append("\n치료 개수를 정확히 알 수 없어 1개 기준으로 계산했어요.\n")
                    .append("실제 보험금은 치료 개수에 따라 달라질 수 있어요.\n");
            return;
        }

        answer.append("\n선택하신 치료 개수 ")
                .append(resolveDentalTreatmentCount(dentalInfo))
                .append("개 기준으로 계산했어요.\n")
                .append("실제 지급 여부는 치료 종류와 약관상 조건에 따라 달라질 수 있어요.\n");
    }

    // 치료형 변환 메서드 추가
    private String getDentalTreatmentDescription(
            DentalTreatmentType dentalTreatmentType
    ) {
        if (dentalTreatmentType == null) {
            return "치료 종류 미확인";
        }

        return switch (dentalTreatmentType) {
            case EXTRACTION -> "발치";
            case CROWN_IMPLANT -> "크라운·임플란트·보철 치료";
            case FILLING -> "충전·보존 치료";
            case ROOT_CANAL -> "신경·근관 치료";
        };
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