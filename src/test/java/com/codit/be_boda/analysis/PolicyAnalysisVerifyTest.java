package com.codit.be_boda.analysis;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;



@SpringBootTest
class PolicyAnalysisVerifyTest {

    @Autowired
    private PolicyAnalysisRepository policyAnalysisRepository;

    @Autowired
    private CoverageItemRepository coverageItemRepository;

    @Autowired
    private ObjectMapper objectMapper;

    //테스트 analysis_id 설정
    private static final Long TEST_ANALYSIS_ID = 20L;

    @Test
    @DisplayName("증권 분석 결과 전체 출력")
    void printPolicyAnalysisResult() throws Exception {

        System.out.println("\n");
        System.out.println("=".repeat(60));
        System.out.println("  증권 분석 결과 확인 | analysis_id = " + TEST_ANALYSIS_ID);
        System.out.println("=".repeat(60));

        // policy_analysis 기본 정보

        PolicyAnalysis analysis = policyAnalysisRepository
                .findById(TEST_ANALYSIS_ID)
                .orElse(null);

        if (analysis == null) {
            System.out.println("X analysis_id=" + TEST_ANALYSIS_ID + " 데이터 없음");
            System.out.println("   → POST /api/upload/policy 먼저 실행하세요");
            return;
        }

        System.out.println("\n[ 1. 증권 기본 정보 ]");
        System.out.println("─".repeat(40));
        System.out.printf("  analysis_id   : %d%n", analysis.getId());
        System.out.printf("  파일명         : %s%n", analysis.getOriginalFileName());
        System.out.printf("  분석 상태       : %s%n", analysis.getAnalysisStatus());
        System.out.printf("  OCR 여부        : %s%n", analysis.getIsOcr() ? "이미지 PDF (OCR)" : "텍스트 PDF");
        System.out.printf("  masked_text 길이: %d 자%n",
                analysis.getMaskedText() != null ? analysis.getMaskedText().length() : 0);
        System.out.printf("  s3_key         : %s%n",
                analysis.getS3Key() != null ? analysis.getS3Key() : "null (원본 파기 완료 또는 임시 모드)");
        System.out.printf("  생성일시        : %s%n", analysis.getCreatedAt());
        System.out.printf("  완료일시        : %s%n", analysis.getCompletedAt());

        //extracted_data (GPT 추출 결과)

        System.out.println("\n[ 2. GPT 추출 결과 (extracted_data) ]");
        System.out.println("─".repeat(40));

        if (analysis.getExtractedData() == null) {
            System.out.println("  X extracted_data 없음 — 분석 실패 또는 진행 중");
        } else {
            Map<String, Object> data = analysis.getExtractedData();
            System.out.printf("  보험사명   : %s%n", data.get("companyName"));
            System.out.printf("  상품명     : %s%n", data.get("productName"));
            System.out.printf("  증권번호   : %s%n", data.get("policyNumber"));
            System.out.printf("  계약자     : %s%n", data.get("contractorName"));
            System.out.printf("  피보험자   : %s%n", data.get("insuredName"));
            System.out.printf("  월 보험료  : %s 원%n", data.get("monthlyPremium"));
            System.out.printf("  보험 시작일: %s%n", data.get("insuranceStartDate"));
            System.out.printf("  보험 종료일: %s%n", data.get("insuranceEndDate"));
            System.out.println("\n  [전체 JSON]");
            System.out.println("  " + objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data));
        }

        //coverage_item (보장 카드 6종)

        System.out.println("\n[ 3. 보장 카드 6종 ]");
        System.out.println("─".repeat(40));

        List<CoverageItem> cards = coverageItemRepository
                .findByPolicyAnalysisOrderByCoverageType(analysis);

        if (cards.isEmpty()) {
            System.out.println("  X 보장 카드 없음 — 분석 실패 또는 진행 중");
        } else {
            System.out.printf("  총 %d개 카드 발견%n%n", cards.size());

            for (CoverageItem card : cards) {
                String detected = Boolean.TRUE.equals(card.getIsDetected()) ? "✅ 감지됨" : "X 미감지";
                System.out.printf("  [%s] %s%n", card.getCoverageType(), detected);

                if (card.getDetail() != null) {
                    System.out.println("  detail:");
                    card.getDetail().forEach((k, v) ->
                            System.out.printf("    %-25s: %s%n", k, v != null ? v : "null (미감지)"));
                } else {
                    System.out.println("  detail: null");
                }

                if (card.getExclusionKeywords() != null) {
                    System.out.printf("  면책 키워드: %s%n", card.getExclusionKeywords());
                }
                if (card.getEvidenceText() != null) {
                    System.out.printf("  근거 원문: %s...%n",
                            card.getEvidenceText().substring(
                                    0, Math.min(50, card.getEvidenceText().length())));
                }
                System.out.println();
            }
        }

        //요약 평가

        System.out.println("[ 4. 요약 평가 ]");
        System.out.println("─".repeat(40));

        if (analysis.getExtractedData() != null) {
            Map<String, Object> data = analysis.getExtractedData();
            long nullCount = data.values().stream().filter(v -> v == null).count();
            System.out.printf("  extracted_data 필드 수: %d개 (null: %d개)%n",
                    data.size(), nullCount);
        }

        long detectedCount = cards.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsDetected())).count();
        long nullDetailCount = cards.stream()
                .filter(c -> c.getDetail() != null)
                .flatMap(c -> c.getDetail().values().stream())
                .filter(v -> v == null).count();

        System.out.printf("  감지된 보장 카드: %d / %d 개%n", detectedCount, cards.size());
        System.out.printf("  null 보장 값 수: %d개%n", nullDetailCount);

        if (nullDetailCount > 0) {
            System.out.println("\n  ⚠  null 값이 있는 이유 (가능성):");
            System.out.println("     1. 증권 PDF에 해당 보장 항목이 실제로 없음");
            System.out.println("     2. GPT가 해당 항목을 인식하지 못함 (프롬프트 개선 필요)");
            System.out.println("     3. OCR 텍스트가 깨져서 금액 인식 실패");
            System.out.printf("     현재 LLM 전달 텍스트: masked_text 앞 15000자 (전체: %d자)%n",
                    analysis.getMaskedText() != null ? analysis.getMaskedText().length() : 0);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  테스트 완료");
        System.out.println("=".repeat(60) + "\n");
    }

    @Test
    @DisplayName("masked_text 앞 500자 미리보기")
    void printMaskedTextPreview() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  masked_text 미리보기 | analysis_id = " + TEST_ANALYSIS_ID);
        System.out.println("=".repeat(60));

        PolicyAnalysis analysis = policyAnalysisRepository
                .findById(TEST_ANALYSIS_ID)
                .orElse(null);

        if (analysis == null || analysis.getMaskedText() == null) {
            System.out.println("X 데이터 없음");
            return;
        }

        String text = analysis.getMaskedText();
        System.out.printf("전체 길이: %d 자%n%n", text.length());
        System.out.println("[ 앞 500자 ]");
        System.out.println(text.substring(0, Math.min(500, text.length())));
        System.out.println("\n[ 4000~4500자 구간 (현재 LLM 전달 경계) ]");
        if (text.length() > 4000) {
            System.out.println(text.substring(4000, Math.min(4500, text.length())));
        } else {
            System.out.println("전체 텍스트가 4000자 미만");
        }

        System.out.println("\n" + "=".repeat(60) + "\n");
    }
}
