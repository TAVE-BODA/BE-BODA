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

import java.util.List;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class CastAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    // CHIP_CLAIM 중 CAST 처리
    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"골절재해".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            return detail.items().stream()
                    .filter(item -> item.coverageName() != null)
                    .filter(item -> item.coverageName().contains("깁스"))
                    .findFirst()
                    .map(item -> buildCastClaimAnswer(
                            item,
                            coverageItem.exclusionKeywords(),
                            request
                    ))
                    .orElse("가입하신 증권에서 깁스 치료와 직접 매칭되는 보장 항목을 찾지 못했어요.");
        }

        return "가입하신 증권에서 골절·깁스 관련 보장 항목을 찾지 못했어요.";
    }

    // CHIP_CLAIM 중 CAST 문자열 응답과 DTO 카드 데이터 생성
    public ClaimAnswerResult generateStructuredClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        CastCoverageMatch match = findMatchedCastCoverage(analysisId);
        String messageContent = generateClaimAnswer(analysisId, request);

        if (match == null) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NOT_AVAILABLE")
                    .summary("깁스 치료와 직접 매칭되는 보장 항목을 찾지 못했어요.")
                    .reasons(List.of(
                            "가입하신 증권에서 골절·깁스 관련 보장 항목이 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "다른 특약이나 약관에 관련 보장이 있는지 추가 확인이 필요할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        CoverageItemDto item = match.item();
        boolean splint = isSplintCast(request);

        List<String> reasons = new ArrayList<>();
        reasons.add("가입하신 증권에서 " + item.coverageName() + " 보장이 확인돼요.");

        if (splint) {
            reasons.add("입력하신 치료 방식은 반깁스 또는 부목이에요.");
            reasons.add("해당 깁스 치료 보장은 부목을 제외하는 조건이 있어요.");
        } else {
            reasons.add("입력하신 치료 방식은 정식 깁스예요.");

            String amountReason = buildCastAmountReason(item);
            if (amountReason != null) {
                reasons.add(amountReason);
            }
        }

        ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                .claimStatus(splint ? "NEEDS_REVIEW" : "POSSIBLE")
                .summary(
                        splint
                                ? "부목 치료는 청구 대상에서 제외될 수 있어요."
                                : "정식 깁스 치료는 청구 가능성이 있어요."
                )
                .reasons(reasons)
                .cautions(buildCastCautions(match.exclusionKeywords()))
                .build();

        return new ClaimAnswerResult(messageContent, claimGuide);
    }

    // CHIP_AMOUNT 중 CAST 처리
    public String generateAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(
                        analysisId
                );

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"골절재해".equals(
                    coverageItem.coverageType()
            )) {
                continue;
            }

            CoverageLlmResponse detail =
                    parseCoverageDetail(
                            coverageItem.detail()
                    );

            if (detail.items() == null
                    || detail.items().isEmpty()) {
                continue;
            }

            CoverageItemDto castItem =
                    detail.items().stream()
                            .filter(item ->
                                    item.coverageName() != null
                            )
                            .filter(item ->
                                    item.coverageName()
                                            .contains("깁스")
                            )
                            .findFirst()
                            .orElse(null);

            if (castItem != null) {
                return buildCastAmountAnswer(
                        castItem,
                        coverageItem.exclusionKeywords(),
                        request
                );
            }
        }

        return "가입하신 증권에서 골절·깁스 관련 보장 항목을 찾지 못했어요.";
    }

    // CHIP_AMOUNT 중 CAST 문자열 응답과 카드 데이터 생성
    public AmountAnswerResult generateStructuredAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent =
                generateAmountAnswer(analysisId, request);

        CastCoverageMatch match =
                findMatchedCastCoverage(analysisId);

        CoverageItemDto castItem =
                match == null ? null : match.item();

        // 증권에서 깁스 보장을 찾지 못한 경우
        if (castItem == null) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "가입하신 증권에서 깁스 치료와 직접 매칭되는 보장 항목을 찾지 못했어요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        // 부목은 보장 제외 조건이므로 계산 불가
        if (isSplintCast(request)) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "입력하신 치료 방식은 반깁스 또는 부목이에요.",
                                    "해당 깁스 치료 보장은 부목을 제외하는 조건이 있어요.",
                                    "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        CoverageAmountDto amount =
                findCastAmount(castItem);

        // 보장은 있지만 금액이 없는 경우
        if (amount == null || amount.coverageAmount() == null) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "깁스 치료 보장은 확인됐지만 정확한 보장금액은 약관 확인이 필요해요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        AmountGuideResponse amountGuide =
                AmountGuideResponse.builder()
                        .calculationAvailable(true)
                        .estimatedItems(List.of(
                                AmountGuideResponse.EstimatedItem.builder()
                                        .coverageName(
                                                castItem.coverageName()
                                        )
                                        .amountText(
                                                String.format(
                                                        "%,d원",
                                                        amount.coverageAmount()
                                                )
                                        )
                                        .reason(
                                                "정식 깁스 치료 시 "
                                                        + buildCastAmountCondition(amount)
                                                        + " 지급되는 보장금액이에요."
                                        )
                                        .build()
                        ))
                        .cautions(List.of(
                                "부목 또는 반깁스는 보장 대상에서 제외될 수 있어요.",
                                "동일한 원인으로 여러 번 깁스 치료를 받은 경우 1회만 지급될 수 있어요.",
                                "실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요."
                        ))
                        .build();

        return new AmountAnswerResult(
                messageContent,
                amountGuide
        );
    }

    // CAST 청구 가능 여부 답변 문장 생성
    private String buildCastClaimAnswer(
            CoverageItemDto item,
            String exclusionKeywords,
            ChatMessageRequest request
    ) {
        String amountText = "";

        CoverageAmountDto amount =
                findCastAmount(item);

        if (amount != null
                && amount.coverageAmount() != null) {

            amountText = String.format(
                    "- %s: %s %,d원\n",
                    item.coverageName(),
                    buildCastAmountCondition(amount),
                    amount.coverageAmount()
            );
        }

        StringBuilder answer = new StringBuilder();

        if (isSplintCast(request)) {
            answer.append("청구가 어려울 수 있어요.\n\n")
                    .append("가입하신 증권에서 깁스 치료 보장은 확인되지만, 해당 보장은 부목을 제외하는 조건이 있어요.\n")
                    .append("입력하신 치료가 반깁스·부목에 해당한다면 청구 대상에서 제외될 수 있습니다.\n\n");
        } else {
            answer.append("청구 가능성이 있어요.\n\n")
                    .append("가입하신 증권에서 깁스 치료 보장이 확인돼요.\n")
                    .append("입력하신 치료가 반깁스·부목이 아니라 정식 깁스라면 청구 가능성이 있습니다.\n\n");
        }

        answer.append("[확인된 보장]\n");

        if (amountText.isBlank()) {
            answer.append("- ").append(item.coverageName()).append("\n");
        } else {
            answer.append(amountText);
        }

        appendExclusionKeywords(answer, exclusionKeywords);

        return answer.toString();
    }

    // CAST 예상 보험금 답변 문장 생성
    private String buildCastAmountAnswer(
            CoverageItemDto item,
            String exclusionKeywords,
            ChatMessageRequest request
    ) {
        if (isSplintCast(request)) {
            return "예상 보험금을 계산하기 어려워요.\n\n"
                    + "가입하신 증권에서 깁스 치료 보장은 확인되지만, 해당 보장은 부목을 제외하는 조건이 있어요.\n"
                    + "입력하신 치료가 반깁스·부목에 해당한다면 지급 대상에서 제외될 수 있습니다.";
        }

        CoverageAmountDto amount =
                findCastAmount(item);

        if (amount == null) {
            return "깁스 치료 보장은 확인됐지만, 예상 보험금 금액은 확인되지 않았어요.";
        }

        if (amount.coverageAmount() == null) {
            return "깁스 치료 보장은 확인됐지만, 예상 보험금 금액은 약관 확인이 필요해요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("예상 보험금은 약 ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("이에요.\n\n");

        answer.append("[계산 내역]\n")
                .append("- ")
                .append(item.coverageName())
                .append(": ")
                .append(buildCastAmountCondition(amount))
                .append(" ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("\n");

        appendExclusionKeywords(answer, exclusionKeywords);

        return answer.toString();
    }

    // 반깁스/부목 여부 확인
    private boolean isSplintCast(ChatMessageRequest request) {
        return request.getCastInfo() != null
                && request.getCastInfo().getCastType() != null
                && "SPLINT".equals(request.getCastInfo().getCastType().name());
    }

    // 주의사항 문구 추가
    private void appendExclusionKeywords(
            StringBuilder answer,
            String exclusionKeywords
    ) {
        if (exclusionKeywords == null || exclusionKeywords.isBlank()) {
            return;
        }

        List<String> castCautions = new ArrayList<>();

        for (String keyword : exclusionKeywords.split(",")) {
            String caution = keyword.trim();

            if (caution.contains("부목") || caution.contains("깁스")) {
                castCautions.add(caution);
            }
        }

        if (castCautions.isEmpty()) {
            return;
        }

        answer.append("\n[주의사항]\n");

        for (String caution : castCautions) {
            answer.append("- ")
                    .append(caution)
                    .append("\n");
        }
    }
    // 증권에서 깁스 보장 항목 조회
    private CastCoverageMatch findMatchedCastCoverage(Long analysisId) {
        if (analysisId == null) {
            return null;
        }

        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"골절재해".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            CoverageItemDto castItem = detail.items().stream()
                    .filter(item -> item.coverageName() != null)
                    .filter(item -> item.coverageName().contains("깁스"))
                    .findFirst()
                    .orElse(null);

            if (castItem != null) {
                return new CastCoverageMatch(
                        castItem,
                        coverageItem.exclusionKeywords()
                );
            }
        }

        return null;
    }

    private String buildCastAmountReason(CoverageItemDto item) {
        CoverageAmountDto amount =
                findCastAmount(item);

        if (amount == null
                || amount.coverageAmount() == null) {
            return null;
        }

        return "깁스 치료 시 "
                + String.format("%,d원", amount.coverageAmount())
                + "의 보장금액이 확인됐어요.";
    }

    // 복합 특약의 여러 금액 중 깁스 치료 금액만 선택
    private CoverageAmountDto findCastAmount(
            CoverageItemDto item
    ) {
        if (item == null
                || item.amounts() == null
                || item.amounts().isEmpty()) {
            return null;
        }

        CoverageAmountDto matchedAmount =
                item.amounts()
                        .stream()
                        .filter(amount ->
                                amount != null
                                        && amount.condition() != null
                                        && normalize(
                                        amount.condition()
                                ).contains("깁스")
                        )
                        .findFirst()
                        .orElse(null);

        if (matchedAmount != null) {
            return matchedAmount;
        }

        // 깁스 단일 보장은 condition이 '조건없음'으로 저장될 수 있음
        if (item.amounts().size() == 1) {
            return item.amounts().get(0);
        }

        // 복합 특약에서 깁스 조건을 찾지 못하면 다른 보장 금액을 사용하지 않음
        return null;
    }

    private String buildCastAmountCondition(
            CoverageAmountDto amount
    ) {
        if (amount.condition() == null
                || amount.condition().isBlank()
                || "조건없음".equals(amount.condition())) {
            return "1회";
        }

        if (normalize(amount.condition())
                .contains("1회당")) {
            return "1회당";
        }

        return amount.condition();
    }

    private List<String> buildCastCautions(String exclusionKeywords) {
        List<String> cautions = new ArrayList<>();

        if (exclusionKeywords != null && !exclusionKeywords.isBlank()) {
            for (String keyword : exclusionKeywords.split(",")) {
                String caution = keyword.trim();

                if (caution.contains("부목") || caution.contains("깁스")) {
                    cautions.add(caution);
                }
            }
        }

        cautions.add("실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요.");

        return cautions;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase()
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "");
    }

    private record CastCoverageMatch(
            CoverageItemDto item,
            String exclusionKeywords
    ) {
    }

    // detail JSON 파싱
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

