package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

// 보험증권 분석 서비스
// 1. PolicyAnalysis 레코드 생성 (PENDING)
// 2. @Async로 비동기 분석 시작
// 3. LLM으로 증권 정보 추출 → extracted_data JSONB 저장
// 4. 보장 카드 6종 생성 → coverage_item 저장
//5. S3 원본 파기.. (현재 연결 x)
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyAnalysisService {

    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final S3Service s3Service;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    // 보장 카드 6종
    private static final List<String> COVERAGE_TYPES =
            List.of("진단비", "수술비", "입원비", "실손", "골절재해", "치아");

    @Transactional
    public PolicyAnalysis createAndStartAnalysis(User user, String originalFileName,
                                                  String s3Key, boolean isOcr,
                                                  String maskedText) {
        PolicyAnalysis analysis = PolicyAnalysis.builder()
                .user(user)
                .originalFileName(originalFileName)
                .s3Key(s3Key)
                .isOcr(isOcr)
                .maskedText(maskedText)
                .build();

        policyAnalysisRepository.save(analysis);
        log.info("[ANALYSIS] 증권 분석 레코드 생성 | analysisId={}", analysis.getId());

        analyzeAsync(analysis);
        return analysis;
    }

    @Async
    @Transactional
    public void analyzeAsync(PolicyAnalysis analysis) {
        long start = System.currentTimeMillis();
        log.info("[ANALYSIS] 증권 비동기 분석 시작 | analysisId={}", analysis.getId());
        analysis.startAnalysis();
        policyAnalysisRepository.save(analysis);

        try {
            Map<String, Object> extractedData = extractPolicyInfo(analysis.getMaskedText());
            analysis.completeAnalysis(extractedData);
            policyAnalysisRepository.save(analysis);
            log.info("[ANALYSIS] 증권 기본 정보 추출 완료 | {}ms", System.currentTimeMillis() - start);

            createCoverageCards(analysis);
            log.info("[ANALYSIS] 보장 카드 생성 완료 | {}ms", System.currentTimeMillis() - start);

            s3Service.deleteFile(analysis.getS3Key());
            analysis.deleteS3Key();
            policyAnalysisRepository.save(analysis);
            log.info("[ANALYSIS] 증권 분석 전체 완료 | 총{}ms", System.currentTimeMillis() - start);

        } catch (Exception e) {
            analysis.failAnalysis(e.getMessage());
            policyAnalysisRepository.save(analysis);
            log.error("[ANALYSIS] 증권 분석 실패 | {}", e.getMessage(), e);
        }
    }


    //LLM으로 증권 기본 정보 추출
    private Map<String, Object> extractPolicyInfo(String maskedText) {
        String prompt = String.format("""
                다음 보험증권 텍스트에서 아래 정보를 추출해줘.
                반드시 JSON 형식으로만 응답해. 다른 텍스트는 절대 포함하지 마.
                찾을 수 없는 값은 null로 반환해.
                
                {
                  "companyName": "보험사명",
                  "productName": "보험 상품명",
                  "contractorName": "계약자명",
                  "insuredName": "피보험자명",
                  "insuranceStartDate": "보험 시작일 (YYYY-MM-DD)",
                  "insuranceEndDate": "보험 종료일 (YYYY-MM-DD)",
                  "monthlyPremium": 월보험료(숫자만, 원 단위),
                  "policyNumber": "증권번호"
                }
                
                보험증권 텍스트:
                %s
                """, maskedText.substring(0, Math.min(15000, maskedText.length())));

        String response = call(prompt);
        try {
            String cleaned = response.replaceAll("```json|```", "").trim();
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ANALYSIS] 증권 정보 파싱 실패 | {}", e.getMessage());
            return Map.of("parseError", e.getMessage());
        }
    }

    //보장 카드 6종 생성 및 DB 저장
    private void createCoverageCards(PolicyAnalysis analysis) {
        for (String coverageType : COVERAGE_TYPES) {
            try {
                Map<String, Object> detail = extractCoverageDetail(
                        analysis.getMaskedText(), coverageType);

                boolean isDetected = Boolean.TRUE.equals(detail.get("isDetected"));
                String exclusionKeywords = (String) detail.get("exclusionKeywords");
                detail.remove("isDetected");
                detail.remove("exclusionKeywords");

                CoverageItem card = CoverageItem.builder()
                        .policyAnalysis(analysis)
                        .coverageType(coverageType)
                        .isDetected(isDetected)
                        .exclusionKeywords(exclusionKeywords)
                        .detail(detail)
                        .build();

                coverageItemRepository.save(card);
                log.info("[ANALYSIS] 보장 카드 저장 | type={} | detected={}", coverageType, isDetected);

            } catch (Exception e) {
                log.error("[ANALYSIS] 보장 카드 생성 실패 | type={} | {}", coverageType, e.getMessage());
            }
        }
    }

   //보장 타입별 LLM 추출
    private Map<String, Object> extractCoverageDetail(String maskedText, String coverageType) {
        String schema = getCoverageSchema(coverageType);

        String prompt = String.format("""
                다음 보험 텍스트에서 '%s' 관련 보장 정보를 추출해줘.
                반드시 JSON 형식으로만 응답해. 다른 텍스트는 절대 포함하지 마.
                감지되지 않으면 isDetected를 false로 반환해.
                
                %s
                
                보험 텍스트:
                %s
                """, coverageType, schema,
                maskedText.substring(0, Math.min(15000, maskedText.length())));

        String response = call(prompt);
        try {
            String cleaned = response.replaceAll("```json|```", "").trim();
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ANALYSIS] 보장 카드 파싱 실패 | type={} | {}", coverageType, e.getMessage());
            return Map.of("isDetected", false);
        }
    }

    //보장 타입별 JSON 스키마 정의
    //새 보험사 추가시 여기에 추가하면 됨.
    // 세부 사항은 여기서 수정하기.. (현재 ocr 성능 문제로 제대로 나오지 않는 것 같음. 항목 비교 후 조금 수정해얗)
    private String getCoverageSchema(String coverageType) {
        return switch (coverageType) {
            case "진단비" -> """
                {
                  "isDetected": true/false,
                  "cancerLimit": 암진단비 한도(원, 없으면 null),
                  "brainLimit": 뇌혈관진단비 한도(원, 없으면 null),
                  "heartLimit": 심장질환진단비 한도(원, 없으면 null),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            case "수술비" -> """
                {
                  "isDetected": true/false,
                  "grade1": 1종 수술비(원, 없으면 null),
                  "grade2": 2종 수술비(원, 없으면 null),
                  "grade3": 3종 수술비(원, 없으면 null),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            case "입원비" -> """
                {
                  "isDetected": true/false,
                  "dailyAmount": 1일 입원일당(원, 없으면 null),
                  "maxDays": 최대 보장일수(없으면 null),
                  "waitingDays": 면책일수(없으면 null),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            case "실손" -> """
                {
                  "isDetected": true/false,
                  "deductibleRate": 자기부담금 비율(소수점, 예: 0.2, 없으면 null),
                  "generation": 실손 세대(예: 4세대, 없으면 null),
                  "duplicateWarning": 중복보장 경고 여부(true/false),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            case "골절재해" -> """
                {
                  "isDetected": true/false,
                  "fractureAmount": 골절진단비(원, 없으면 null),
                  "disasterAmount": 재해사고보장금(원, 없으면 null),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            case "치아" -> """
                {
                  "isDetected": true/false,
                  "treatmentAmount": 치아치료비(원, 없으면 null),
                  "implantAmount": 임플란트 보장(원, 없으면 null),
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
            default -> """
                {
                  "isDetected": true/false,
                  "exclusionKeywords": "면책 키워드 요약"
                }
                """;
        };
    }

    private String call(String prompt) {
        return ChatClient.builder(chatModel).build().prompt()
                .options(OpenAiChatOptions.builder()
                        .model(miniModel)
                        .temperature(0.0)
                        .build())
                .user(prompt)
                .call()
                .content();
    }
}
