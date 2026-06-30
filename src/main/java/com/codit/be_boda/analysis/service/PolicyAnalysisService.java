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
// 5. S3 원본 파기.. (현재 연결 x)
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
            List.of("진단", "수술", "입원", "실손", "골절재해", "치아");

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

                detail.putIfAbsent("items", List.of());

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
            return objectMapper.readValue(cleaned, new TypeReference<>() {
            });
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
            case "진단" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "진단 관련 보장항목명",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 1년이내, 1년 초과, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    """;
            case "수술" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "수술 관련 보장항목명. 예: 1종 수술, 2종 수술, 3종 수술, 4종 수술, 5종 수술(고도·특수), 장기이식 수술, 수술 종별 기준",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 1년 이내, 1년 초과, 보험기간 전체, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    수술 보장은 1종/2종/3종/4종/5종 수술처럼 종별로 금액이 다른 경우가 많아.
                    이 경우 하나의 item으로 합치지 말고, 각 종별 수술을 별도의 item으로 분리해줘.
                    예: "1종 수술", "2종 수술", "3종 수술", "장기이식 수술"은 각각 다른 item으로 생성해줘.

                    수술 종별 기준표가 증권에 없고 약관 확인이 필요한 경우에는
                    coverageName을 "수술 종별 기준"으로 하는 item을 추가하고,
                    condition에는 "약관이 필요해요",
                    coverageAmount에는 null을 넣어.
                    """;
            case "입원" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "입원 관련 보장항목명. 예: 질병 입원일당, 재해 입원일당, 2·3인실 입원, 상급병실 1인실, 1회 입원 한도, 최대 보장 기간",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 1일당, 3일 초과 1일당, 보험기간 전체, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    입원 보장은 입원 1일당 지급되는 금액을 중심으로 추출해.
                    증권에 여러 입원 보장항목이 나열되어 있으면 하나로 합치지 말고 각각 별도의 item으로 분리해줘.
                    예: "질병 입원일당", "재해 입원일당", "2·3인실 입원", "상급병실 1인실", "상급병실 1인실(종합병원)", "상급병실 1인실(상급종합병원)"은 각각 다른 item으로 생성한다.
                    
                    금액이 "1만원/일", "4만원/일", "3,000원/일"처럼 1일당 지급이면
                    coverageAmount에는 숫자만 원 단위로 넣고,
                    condition에는 "1일당"이라고 작성해줘.
                    
                    "3일 초과"처럼 특정 일수 이후부터 지급되는 조건이 있으면
                    condition에 "3일 초과 1일당"처럼 작성해줘.
                    
                    "1회 입원 한도", "최대 보장 기간"처럼 금액이 아니라 일수 제한인 경우에도 item으로 추가해.
                    이 경우 coverageName에는 "1회 입원 한도" 또는 "최대 보장 기간"을 넣고,
                    condition에는 제한 조건을 작성하며,
                    coverageAmount에는 일수를 숫자로 넣는다.
                    예: 30일이면 coverageAmount는 30, condition은 "일수 한도"로 작성해줘.
                    
                    "1회 120일, 연 3회"처럼 숫자 하나로 표현하기 어려운 제한 조건은
                    coverageName을 "최대 보장 기간"으로 하고,
                    condition에 "1회 120일, 연 3회"라고 작성하며,
                    coverageAmount에는 null을 넣어줘.
                    """;
            case "실손" -> """
                    {
                      "isDetected": true/false,
                      "items": [],
                      "exclusionKeywords": null
                    }
                    
                    실손 보장은 일반 보장 카드처럼 금액 항목을 추출하지 않는다.
                    실손이 확인된 경우에는 세대 정보만 추출한다.
                    예: "1세대 가입 확인", "2세대 가입 확인", "3세대 가입 확인", "4세대 가입 확인".
                    
                    실손 세대가 확인되면 items에는 coverageName이 "실손 세대"인 item 하나만 생성한다.
                    condition에는 "3세대 가입 확인"처럼 작성하고, coverageAmount에는 null을 넣는다.
                    
                    실손 보장이 확인되지 않으면 isDetected는 false, items는 빈 배열 [], exclusionKeywords는 null로 작성한다.
                   
                    """;
            case "골절재해" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "골절·재해 관련 보장항목명. 예: 재해골절 진단, 5대 재해골절 진단, 재해골절 수술, 재해 화상, 재해 장해, 깁스(Cast) 치료, 보장 범위",
                            "amounts": [
                              {
                                "condition" :  "기간/조건명. 예: 보험기간 전체, 조건없음, 약관이 필요해요",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    골절·재해 보장은 재해로 인한 골절, 화상, 장해, 깁스 치료, 골절 수술 등에 대해 지급되는 보장이야.
                    증권에 여러 보장항목이 나열되어 있으면 하나로 합치지 말고 각각 별도의 item으로 분리해.
                    예: "재해골절 진단", "5대 재해골절 진단", "재해골절 수술", "재해 화상", "재해 장해", "깁스(Cast) 치료"는 각각 다른 item으로 생성한다.
                    
                    골절·재해 보장에서 치아파절 제외, 현저한 추상, 추상, 장해율 조건, 80% 이상 조건 등이 있으면 coverageName에 포함하여 구분한다.
                    예: "재해골절 진단 (치아 파절 제외)", "재해 화상 — 현저한 추상", "재해 장해 (80% 이상)"처럼 작성해줘.
                    
                    보장 범위가 증권에 명확히 설명되어 있지 않고 약관 확인이 필요한 경우에는
                    coverageName을 "보장 범위"로 하는 item을 추가하고,
                    condition에는 "약관이 필요해요",
                    coverageAmount에는 null을 넣어.
                    
                    금액이 "10만원/회"처럼 회당 지급이면 coverageAmount에는 숫자만 원 단위로 넣고,
                    condition에는 "회당" 또는 "1회당"이라고 작성해.
                    """;
            case "치아" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "치아 치료 관련 보장항목명. 예: 영구치 발치 치료, 가철성의치(틀니) — 보철물당, 고정성가공의치(브릿지), 임플란트, 크라운 치료, 인레이·온레이, 복합레진, 아말감·글래스아이오노머, 영구치 발치 개당",
                            "amounts": [
                              {
                                "condition" :  "기간/조건명. 예: 2년 이내, 2년 초과, 1년 이내, 1년 초과, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    치아 보장은 치아 치료에 지급되는 보장항목을 추출해.
                    하나의 치아 특약 안에 여러 치료 항목이 묶여 있는 경우가 많으므로, 치료 항목별로 각각 별도의 item으로 분리해.
                    
                    치아 치료 항목은 같은 면책기간을 가진 치료끼리 묶어서 추출해.
                    예: 보철 치료는 2년 이내/2년 초과, 크라운 치료는 1년 이내/1년 초과, 보존 치료는 1년 이내/1년 초과처럼 구분해줘.
                    
                    그룹 제목이 필요한 경우에는 coverageName을 그룹명으로 하는 item을 먼저 추가해.
                    이때 coverageAmount는 null로 작성해.
                    예: coverageName은 "영구치 발치 치료", condition은 "2년 이내", coverageAmount는 null.
                    
                    각 치료 항목은 면책기간 이내 금액과 면책기간 초과 금액을 모두 amounts 배열에 넣는다.
                    예: 가입 후 2년 이내 2.5만원, 2년 초과 5만원이면
                    amounts에 {"condition": "2년 이내", "coverageAmount": 25000},
                    {"condition": "2년 초과", "coverageAmount": 50000} 두 개를 넣는다.
                    
                    면책기간이 없는 항목은 amounts를 하나만 만들고 condition에는 "조건없음"이라고 작성해.
                    
                    금액 단위가 "원/개", "원/보철물당", "원/회"처럼 표시되어 있으면
                    coverageAmount에는 숫자만 원 단위로 넣고,
                    단위 정보는 coverageName에 포함한다.
                    예: "가철성의치(틀니) — 보철물당", "고정성가공의치(브릿지) — 개당", "영구치 발치 — 개당".
                    
                    치료 사유 설명, 치아우식증, 치주질환, 보장개시일 계산 문구는 추출하지 않아.
                    결과적으로 지급 금액을 구분하는 "이내/초과" 조건과 치료명, 금액만 추출해.
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
