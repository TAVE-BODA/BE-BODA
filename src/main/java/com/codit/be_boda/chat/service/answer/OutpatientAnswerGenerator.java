package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutpatientAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    public String generateClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        List<CoverageItemDto> matchedItems =
                findMatchedOutpatientItems(analysisId);

        if (matchedItems.isEmpty()) {
            return """
                    현재 연결된 증권 분석 결과에서 통원·실손 관련 보장을 확인하지 못했어요.
                    
                    입력하신 치료 유형은 통원·외래 치료이지만, 현재 증권에서는 직접 매칭되는 통원비 또는 실손 보장 항목이 확인되지 않았어요.
                    
                    다른 증권이나 별도의 실손의료보험에 가입되어 있는지 확인해 주세요.
                    정확한 보장 여부는 약관 또는 보험사를 통해 추가 확인이 필요할 수 있어요.
                    """;
        }

        StringBuilder answer = new StringBuilder();

        answer.append("통원·외래 치료와 관련된 보장 후보가 있어요.\n\n")
                .append("가입하신 증권에서 통원 또는 실손 관련 보장 항목이 확인돼요.\n")
                .append("다만 실제 지급 여부와 금액은 진료비 내역, 자기부담금, 공제금액 등의 확인이 필요해요.\n\n")
                .append("[확인된 후보]\n");

        for (CoverageItemDto item : matchedItems) {
            answer.append("- ")
                    .append(item.coverageName());

            String amountText = buildAmountText(item);

            if (!amountText.isBlank()) {
                answer.append(": ")
                        .append(amountText);
            }

            answer.append("\n");
        }

        return answer.toString();
    }

    // CHIP_CLAIM 중 OUTPATIENT 문자열 응답과 DTO 카드 데이터 생성
    public ClaimAnswerResult generateStructuredClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent = generateClaimAnswer(analysisId, request);

        List<CoverageItemDto> matchedItems =
                findMatchedOutpatientItems(analysisId);

        if (matchedItems.isEmpty()) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NOT_AVAILABLE")
                    .summary("현재 증권 분석 결과에서 통원·실손 관련 보장을 확인하지 못했어요.")
                    .reasons(List.of(
                            "입력하신 치료 유형은 통원·외래 치료예요.",
                            "현재 연결된 증권에서 직접 매칭되는 통원비 또는 실손 보장 항목이 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "다른 증권이나 별도의 실손의료보험에 가입되어 있는지 확인해 주세요.",
                            "증권에 표시되지 않은 조건은 약관 또는 보험사를 통해 추가 확인이 필요할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("입력하신 치료 유형은 통원·외래 치료예요.");

        for (CoverageItemDto item : matchedItems) {
            String amountText = buildAmountText(item);

            if (amountText.isBlank()) {
                reasons.add(
                        "가입하신 증권에서 "
                                + item.coverageName()
                                + " 보장 후보가 확인돼요."
                );
            } else {
                reasons.add(
                        "가입하신 증권에서 "
                                + item.coverageName()
                                + " 보장 후보가 확인돼요: "
                                + amountText
                );
            }
        }

        ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                .claimStatus("NEEDS_REVIEW")
                .summary("통원·실손 관련 보장 후보가 확인됐지만 세부 조건 확인이 필요해요.")
                .reasons(reasons)
                .cautions(List.of(
                        "실제 지급 여부는 진단명과 치료 내용에 따라 달라질 수 있어요.",
                        "진료비 영수증과 세부내역서 확인이 필요해요.",
                        "실손 보장은 자기부담금, 공제금액 및 통원 한도에 따라 지급금액이 달라질 수 있어요.",
                        "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                ))
                .build();

        return new ClaimAnswerResult(messageContent, claimGuide);
    }

    public String generateAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        List<CoverageItemDto> matchedItems =
                findMatchedOutpatientItems(analysisId);

        if (matchedItems.isEmpty()) {
            return """
                    현재 증권 분석 결과에서 통원·실손 관련 보장을 확인하지 못했어요.
                    
                    다른 증권이나 별도의 실손의료보험 가입 여부를 확인해 주세요.
                    """;
        }

        return """
                통원·외래 치료의 예상 보험금은 현재 단계에서 정확히 계산하기 어려워요.
                
                관련 보장 후보는 확인됐지만 실제 진료비, 급여·비급여 항목, 자기부담금, 공제금액 및 통원 한도 확인이 필요해요.
                
                진료비 영수증과 세부내역서를 기준으로 약관 조건을 추가 확인해 주세요.
                """;
    }

    // CHIP_AMOUNT 중 OUTPATIENT 문자열 응답과 카드 데이터 생성
    public AmountAnswerResult generateStructuredAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent =
                generateAmountAnswer(analysisId, request);

        List<CoverageItemDto> matchedItems =
                findMatchedOutpatientItems(analysisId);

        if (matchedItems.isEmpty()) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "현재 증권 분석 결과에서 통원비 또는 실손 보장 항목을 확인하지 못했어요.",
                                    "다른 증권이나 별도의 실손의료보험 가입 여부를 확인해 주세요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        List<AmountGuideResponse.EstimatedItem> estimatedItems =
                matchedItems.stream()
                        .map(item -> {
                            String amountText =
                                    buildAmountText(item);

                            return AmountGuideResponse.EstimatedItem.builder()
                                    .coverageName(
                                            item.coverageName()
                                    )
                                    .amountText(
                                            amountText.isBlank()
                                                    ? "진료비 내역 확인 필요"
                                                    : amountText
                                    )
                                    .reason(
                                            "실제 진료비와 자기부담금, 공제금액을 확인해야 지급금액을 계산할 수 있어요."
                                    )
                                    .build();
                        })
                        .toList();

        AmountGuideResponse amountGuide =
                AmountGuideResponse.builder()
                        .calculationAvailable(false)
                        .estimatedItems(estimatedItems)
                        .cautions(List.of(
                                "진료비 영수증과 진료비 세부내역서 확인이 필요해요.",
                                "실손 보장은 자기부담금, 공제금액 및 통원 한도에 따라 지급금액이 달라질 수 있어요.",
                                "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                        ))
                        .build();

        return new AmountAnswerResult(
                messageContent,
                amountGuide
        );
    }

    private List<CoverageItemDto> findMatchedOutpatientItems(
            Long analysisId
    ) {
        if (analysisId == null) {
            return List.of();
        }

        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        List<CoverageItemDto> matchedItems = new ArrayList<>();

        for (CoverageItemInfo coverageItem : coverageItems) {
            CoverageLlmResponse detail =
                    parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            for (CoverageItemDto item : detail.items()) {
                if (isOutpatientCoverage(coverageItem, item)) {
                    matchedItems.add(item);
                }
            }
        }

        return matchedItems;
    }

    private boolean isOutpatientCoverage(
            CoverageItemInfo coverageItem,
            CoverageItemDto item
    ) {
        String coverageType = normalize(coverageItem.coverageType());
        String coverageName = normalize(item.coverageName());

        if (isInformationalOutpatientItem(item)) {
            return false;
        }

        return coverageType.contains("통원")
                || coverageType.contains("실손")
                || coverageName.contains("통원")
                || coverageName.contains("외래")
                || coverageName.contains("실손")
                || coverageName.contains("처방조제");
    }

    private boolean isInformationalOutpatientItem(
            CoverageItemDto item
    ) {
        String coverageName = normalize(item.coverageName());

        if (coverageName.equals("실손세대")
                || coverageName.contains("가입시확인사항")) {
            return true;
        }

        if (item.amounts() == null || item.amounts().isEmpty()) {
            return false;
        }

        return item.amounts().stream()
                .map(amount -> normalize(amount.condition()))
                .anyMatch(condition ->
                        condition.contains("가입시확인사항")
                                || condition.contains("가입관련안내")
                                || condition.contains("관련안내가확인")
                                || condition.contains("안내문구")
                                || condition.contains("안내사항")
                                || condition.contains("실제발생한비용")
                                || condition.contains("2개이상가입")
                );
    }

    private String buildAmountText(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return "";
        }

        List<String> amountTexts = new ArrayList<>();

        for (CoverageAmountDto amount : item.amounts()) {
            String condition = amount.condition();
            String amountText = amount.coverageAmount() == null
                    ? "금액 확인 필요"
                    : String.format("%,d원", amount.coverageAmount());

            if (condition == null
                    || condition.isBlank()
                    || "조건없음".equals(condition)) {
                amountTexts.add(amountText);
            } else {
                amountTexts.add(condition + " " + amountText);
            }
        }

        return String.join(", ", amountTexts);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("\\s+", "");
    }

    private CoverageLlmResponse parseCoverageDetail(String detail) {
        if (detail == null
                || detail.isBlank()
                || "null".equalsIgnoreCase(detail)) {
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