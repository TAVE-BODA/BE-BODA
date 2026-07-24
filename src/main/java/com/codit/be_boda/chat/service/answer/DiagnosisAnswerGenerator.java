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

// 진단만 받았어요 선택 시 답변
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;


    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        // 1. message가 비어 있으면 진단명을 입력하라고 안내
        if (isBlank(request.getMessage())) {
            return buildNeedDiagnosisNameAnswer();
        }

        // 2. 애매한 입력이면 정확한 입력 요구
        if (isAmbiguousDiagnosisMessage(request.getMessage())) {
            return buildAmbiguousDiagnosisAnswer();
        }

        // 3. 증권 분석 결과에서 입력 진단명과 매칭되는 진단비 후보 찾기
        List<CoverageItemDto> matchedItems =
                findMatchedDiagnosisItems(analysisId, request.getMessage());

        // 4. 입력은 구체적인데 증권에서 후보가 없으면 '현재 증권 분석 결과에서 찾지 못함' 안내
        if (matchedItems.isEmpty()) {
            return buildNoDiagnosisCoverageAnswer();
        }

        // 5. 후보가 있으면 청구 가능성이 있는 보장 후보로 안내
        StringBuilder answer = new StringBuilder();

        answer.append("입력하신 진단명과 관련된 보장 후보를 찾았어요.\n\n")
                .append("진단비는 진단명, 질병코드, 최초 1회 지급 조건, 감액기간 등에 따라 달라질 수 있어요.\n")
                .append("따라서 현재 단계에서는 청구 가능성이 있는 후보를 먼저 안내드릴게요.\n\n")
                .append("[확인된 후보]\n")
                .append(buildDiagnosisCandidateLines(matchedItems))
                .append("\n정확한 청구 가능 여부는 진단서의 병명 또는 질병코드와 약관 조건 확인이 필요해요.");

        return answer.toString();
    }

    // CHIP_CLAIM 중 DIAGNOSIS_ONLY 문자열 응답과 DTO 카드 데이터 생성
    public ClaimAnswerResult generateStructuredClaimAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent = generateClaimAnswer(analysisId, request);
        String diagnosisMessage = request.getMessage();

        if (isBlank(diagnosisMessage)) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NEEDS_REVIEW")
                    .summary("청구 가능성 확인을 위해 진단명이 필요해요.")
                    .reasons(List.of(
                            "입력된 진단명이 없어 관련 진단비 보장을 검색할 수 없어요."
                    ))
                    .cautions(List.of(
                            "진단서나 진료확인서에 적힌 병명 또는 질병코드를 입력해 주세요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        if (isAmbiguousDiagnosisMessage(diagnosisMessage)) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NEEDS_REVIEW")
                    .summary("진단명을 조금 더 구체적으로 입력해 주세요.")
                    .reasons(List.of(
                            "현재 입력만으로는 어떤 진단비 보장과 관련된 내용인지 판단하기 어려워요."
                    ))
                    .cautions(List.of(
                            "진단서의 병명 또는 질병코드를 입력하면 더 정확하게 확인할 수 있어요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<CoverageItemDto> matchedItems =
                findMatchedDiagnosisItems(analysisId, diagnosisMessage);

        if (matchedItems.isEmpty()) {
            ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                    .claimStatus("NOT_AVAILABLE")
                    .summary("입력하신 진단명과 매칭되는 보장 후보를 찾지 못했어요.")
                    .reasons(List.of(
                            "현재 증권 분석 결과에서 입력하신 질환과 직접 매칭되는 진단비 보장이 확인되지 않았어요."
                    ))
                    .cautions(List.of(
                            "다른 특약이나 약관 원문에 관련 보장이 있는지 추가 확인이 필요할 수 있어요.",
                            "진단서의 정확한 병명이나 질병코드가 있다면 다시 입력해 주세요."
                    ))
                    .build();

            return new ClaimAnswerResult(messageContent, claimGuide);
        }

        List<String> reasons = buildDiagnosisReasons(matchedItems);
        List<String> cautions = buildDiagnosisCautions(matchedItems);

        ClaimGuideResponse claimGuide = ClaimGuideResponse.builder()
                .claimStatus("NEEDS_REVIEW")
                .summary("관련 진단비 보장 후보가 확인됐지만 정확한 진단 조건 확인이 필요해요.")
                .reasons(reasons)
                .cautions(cautions)
                .build();

        return new ClaimAnswerResult(messageContent, claimGuide);
    }


    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        if (isBlank(request.getMessage())) {
            return buildNeedDiagnosisNameAnswer();
        }

        if (isAmbiguousDiagnosisMessage(request.getMessage())) {
            return buildAmbiguousDiagnosisAnswer();
        }

        List<CoverageItemDto> matchedItems =
                findMatchedDiagnosisItems(analysisId, request.getMessage());

        if (matchedItems.isEmpty()) {
            return buildNoDiagnosisCoverageAnswer();
        }

        StringBuilder answer = new StringBuilder();

        answer.append("입력하신 진단명과 관련된 진단비 후보를 찾았어요.\n\n")
                .append("다만 진단비는 최초 1회 지급 여부, 가입 후 경과 기간, 질병코드, 감액 조건에 따라 달라질 수 있어요.\n")
                .append("그래서 현재 단계에서는 하나의 예상 보험금으로 합산하지 않고 후보 금액을 안내드릴게요.\n\n")
                .append("[확인된 후보]\n")
                .append(buildDiagnosisCandidateLines(matchedItems))
                .append("\n정확한 예상 보험금 계산을 위해서는 진단서의 병명 또는 질병코드 확인이 필요해요.");

        return answer.toString();
    }

    // CHIP_AMOUNT 중 DIAGNOSIS_ONLY 문자열 응답과 카드 데이터 생성
    public AmountAnswerResult generateStructuredAmountAnswer(
            Long analysisId,
            ChatMessageRequest request
    ) {
        String messageContent =
                generateAmountAnswer(analysisId, request);

        String diagnosisMessage =
                request.getMessage();

        if (isBlank(diagnosisMessage)) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "예상 진단비 확인을 위해 진단서에 적힌 병명 또는 질병코드가 필요해요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        if (isAmbiguousDiagnosisMessage(diagnosisMessage)) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "정확한 예상 진단비 확인을 위해 진단명을 조금 더 구체적으로 입력해 주세요."
                            ))
                            .build();

            return new AmountAnswerResult(
                    messageContent,
                    amountGuide
            );
        }

        List<CoverageItemDto> matchedItems =
                findMatchedDiagnosisItems(
                        analysisId,
                        diagnosisMessage
                );

        if (matchedItems.isEmpty()) {
            AmountGuideResponse amountGuide =
                    AmountGuideResponse.builder()
                            .calculationAvailable(false)
                            .estimatedItems(List.of())
                            .cautions(List.of(
                                    "현재 증권 분석 결과에서 입력하신 진단명과 매칭되는 진단비 보장 후보를 찾지 못했어요."
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
                                    buildDiagnosisAmountSummary(item);

                            return AmountGuideResponse.EstimatedItem.builder()
                                    .coverageName(
                                            item.coverageName()
                                    )
                                    .amountText(
                                            amountText.isBlank()
                                                    ? "약관 확인 필요"
                                                    : amountText
                                    )
                                    .reason(
                                            "진단서의 병명 또는 질병코드가 약관상 지급 조건과 일치하는지 확인이 필요해요."
                                    )
                                    .build();
                        })
                        .toList();

        List<String> cautions =
                new ArrayList<>(
                        buildDiagnosisCautions(matchedItems)
                );

        cautions.add(
                0,
                "여러 진단비 후보를 하나의 예상 보험금으로 합산하지 않았어요."
        );

        AmountGuideResponse amountGuide =
                AmountGuideResponse.builder()
                        .calculationAvailable(false)
                        .estimatedItems(estimatedItems)
                        .cautions(cautions)
                        .build();

        return new AmountAnswerResult(
                messageContent,
                amountGuide
        );
    }

    // 입력된 자유 입력이 애매한지 판단
    private boolean isAmbiguousDiagnosisMessage(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }

        String normalizedMessage = normalize(message);

        return normalizedMessage.equals("진단")
                || normalizedMessage.equals("진단받았어요")
                || normalizedMessage.equals("질병")
                || normalizedMessage.equals("상해")
                || normalizedMessage.equals("아픔")
                || normalizedMessage.equals("아파요")
                || normalizedMessage.equals("검사")
                || normalizedMessage.equals("검사했어요")
                || normalizedMessage.equals("치료")
                || normalizedMessage.equals("치료받았어요")
                || normalizedMessage.equals("병원")
                || normalizedMessage.equals("병원다녀옴")
                || normalizedMessage.equals("병원다녀왔어요");
    }

    // 진단지 후보를 찾는 메서드
    private List<CoverageItemDto> findMatchedDiagnosisItems(Long analysisId, String message) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        List<String> targetKeywords = getDiagnosisKeywords(message);

        if (targetKeywords.isEmpty()) {
            return List.of();
        }

        List<CoverageItemDto> matchedItems = new ArrayList<>();

        for (CoverageItemInfo coverageItem : coverageItems) {
            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            for (CoverageItemDto item : detail.items()) {
                if (item.coverageName() == null || item.coverageName().isBlank()) {
                    continue;
                }

                if (!isDiagnosisCoverage(coverageItem, item)) {
                    continue;
                }

                if (matchesAnyKeyword(item.coverageName(), targetKeywords)) {
                    matchedItems.add(item);
                }
            }
        }

        return matchedItems;
    }


    // 진단지 계열인지 판단
    private boolean isDiagnosisCoverage(CoverageItemInfo coverageItem, CoverageItemDto item) {
        String coverageType = coverageItem.coverageType();
        String coverageName = item.coverageName();

        if (coverageType != null && coverageType.contains("진단")) {
            return true;
        }

        return coverageName != null
                && (coverageName.contains("진단비")
                || coverageName.contains("진단보험금")
                || coverageName.contains("진단"));
    }

    // 검색 키워드 목록 생성
    private List<String> getDiagnosisKeywords(String message) {
        String normalizedMessage = normalize(message);
        List<String> keywords = new ArrayList<>();

        if (normalizedMessage.contains("화상") || normalizedMessage.contains("부식")) {
            keywords.add("화상");
            keywords.add("부식");
        }

        if (normalizedMessage.contains("디스크") || normalizedMessage.contains("추간판")) {
            keywords.add("디스크");
            keywords.add("추간판");
        }

        if (normalizedMessage.contains("뇌혈관")
                || normalizedMessage.contains("뇌졸중")
                || normalizedMessage.contains("뇌출혈")
                || normalizedMessage.contains("뇌경색")) {
            keywords.add("뇌혈관");
            keywords.add("뇌졸중");
            keywords.add("뇌출혈");
            keywords.add("뇌경색");
        }

        if (normalizedMessage.contains("허혈성")
                || normalizedMessage.contains("심장")
                || normalizedMessage.contains("협심증")
                || normalizedMessage.contains("심근경색")) {
            keywords.add("허혈성");
            keywords.add("심장");
            keywords.add("협심증");
            keywords.add("심근경색");
        }

        if (normalizedMessage.contains("크론")) {
            keywords.add("크론");
        }

        if (normalizedMessage.contains("궤양성대장염")
                || normalizedMessage.contains("대장염")) {
            keywords.add("궤양성대장염");
            keywords.add("대장염");
        }

        if (normalizedMessage.contains("원형탈모")
                || normalizedMessage.contains("탈모")) {
            keywords.add("원형탈모");
            keywords.add("탈모");
        }

        if (normalizedMessage.contains("소화계")) {
            keywords.add("소화계");
        }

        if (normalizedMessage.contains("용종")
                || normalizedMessage.contains("폴립")
                || normalizedMessage.contains("선종")) {
            keywords.add("용종");
            keywords.add("폴립");
            keywords.add("선종");
        }

        if (normalizedMessage.contains("암")) {
            keywords.add("암");
            keywords.add("소액암");
            keywords.add("유사암");
        }

        if (keywords.isEmpty()
                && isSimpleDiagnosisName(message)) {
            keywords.add(message.trim());
        }

        return keywords;
    }

    private boolean isSimpleDiagnosisName(
            String message
    ) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage =
                normalize(message);

        if (normalizedMessage.length() > 20) {
            return false;
        }

        return !normalizedMessage.contains("받았")
                && !normalizedMessage.contains("했어")
                && !normalizedMessage.contains("했어요")
                && !normalizedMessage.contains("병원")
                && !normalizedMessage.contains("검사")
                && !normalizedMessage.contains("치료");
    }

    private boolean matchesAnyKeyword(String coverageName, List<String> keywords) {
        String normalizedCoverageName = normalize(coverageName);

        return keywords.stream()
                .map(this::normalize)
                .anyMatch(normalizedCoverageName::contains);
    }

    private String buildDiagnosisCandidateLines(List<CoverageItemDto> matchedItems) {
        StringBuilder builder = new StringBuilder();

        for (CoverageItemDto item : matchedItems) {
            builder.append("- ")
                    .append(item.coverageName());

            if (item.amounts() == null || item.amounts().isEmpty()) {
                builder.append(": 금액 약관 확인 필요\n");
                continue;
            }

            if (item.amounts().size() == 1) {
                CoverageAmountDto amount = item.amounts().get(0);

                builder.append(": ")
                        .append(formatCondition(amount.condition()))
                        .append(" ")
                        .append(formatAmount(amount.coverageAmount()))
                        .append("\n");
                continue;
            }

            builder.append("\n");

            for (CoverageAmountDto amount : item.amounts()) {
                builder.append("  - ")
                        .append(formatCondition(amount.condition()))
                        .append(": ")
                        .append(formatAmount(amount.coverageAmount()))
                        .append("\n");
            }
        }

        return builder.toString();
    }

    private List<String> buildDiagnosisReasons(
            List<CoverageItemDto> matchedItems
    ) {
        List<String> reasons = new ArrayList<>();

        for (CoverageItemDto item : matchedItems) {
            String amountSummary = buildDiagnosisAmountSummary(item);

            if (amountSummary.isBlank()) {
                reasons.add(
                        item.coverageName()
                                + " 보장 후보가 확인됐어요."
                );
            } else {
                reasons.add(
                        item.coverageName()
                                + " 보장 후보가 확인됐어요: "
                                + amountSummary
                );
            }
        }

        return reasons;
    }

    private String buildAmbiguousDiagnosisAnswer() {
        return """
                진단명을 조금 더 정확히 입력해 주세요.
                
                현재 입력만으로는 어떤 진단비 보장과 관련된 내용인지 판단하기 어려워요.
                진단서나 진료확인서에 적힌 병명 또는 질병코드를 입력해 주세요.
                
                예: 화상, 허리디스크, 뇌혈관질환, 허혈성심장질환, 크론병, 원형탈모증
                """;
    }

    private String buildNoDiagnosisCoverageAnswer() {
        return """
                입력하신 진단명과 일치하는 진단비 보장 후보를 현재 증권 분석 결과에서 찾지 못했어요.
                
                다만 실제 보장 여부는 약관 원문, 질병코드, 보험사 심사 기준에 따라 달라질 수 있어요.
                진단서에 적힌 정확한 병명이나 질병코드가 있다면 다시 입력해 주세요.
                """;
    }

    private String buildNeedDiagnosisNameAnswer() {
        return """
                진단명을 입력해 주세요.
                
                진단비는 병명에 따라 보장 여부와 금액이 크게 달라져요.
                진단서나 진료확인서에 적힌 병명 또는 진단명을 입력해 주세요.
                
                예: 화상 진단, 허리디스크, 뇌혈관질환, 허혈성심장질환, 크론병, 원형탈모증
                """;
    }

    private String formatCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return "조건없음";
        }

        return condition;
    }

    private String formatAmount(Long amount) {
        if (amount == null) {
            return "약관 확인 필요";
        }

        return String.format("%,d원", amount);
    }

    private String buildDiagnosisAmountSummary(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return "";
        }

        List<String> amountTexts = new ArrayList<>();

        for (CoverageAmountDto amount : item.amounts()) {
            String condition = amount.condition();
            String amountText = formatAmount(amount.coverageAmount());

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

    private List<String> buildDiagnosisCautions(
            List<CoverageItemDto> matchedItems
    ) {
        List<String> cautions = new ArrayList<>();

        boolean hasToothFractureExclusion = matchedItems.stream()
                .map(CoverageItemDto::coverageName)
                .filter(name -> name != null)
                .anyMatch(name ->
                        name.contains("치아 파절")
                                || name.contains("치아파절")
                );

        boolean hasFiveMajorFracture = matchedItems.stream()
                .map(CoverageItemDto::coverageName)
                .filter(name -> name != null)
                .anyMatch(name -> name.contains("5대"));

        if (hasToothFractureExclusion) {
            cautions.add("재해골절 진단 보장은 치아 파절이 제외될 수 있어요.");
        }

        if (hasFiveMajorFracture) {
            cautions.add("5대 재해골절 해당 여부에 따라 적용되는 보장금액이 달라질 수 있어요.");
        }

        cautions.add("진단서의 병명 또는 질병코드와 약관상 지급 조건 확인이 필요해요.");
        cautions.add("실제 지급 여부는 보험사 심사 결과 및 약관 조건에 따라 달라질 수 있어요.");

        return cautions;
    }


    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.replace(" ", "")
                .replace("\n", "")
                .replace("\t", "")
                .toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }


    private CoverageLlmResponse parseCoverageDetail(String detail) {
        if (detail == null || detail.isBlank() || "null".equalsIgnoreCase(detail)) {
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