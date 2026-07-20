package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.dashboard.service.DashboardService;
import com.codit.be_boda.upload.service.S3Service;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 증권 비동기 분석 전용 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPolicyAnalysisService {

    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final S3Service s3Service;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final DashboardService dashboardService;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    private static final List<String> COVERAGE_TYPES =
            List.of("진단", "수술", "입원", "실손", "골절재해", "치아");

    @Async
    @Transactional
    public void analyzeAsync(PolicyAnalysis analysis, Long chatSessionId) {
        long start = System.currentTimeMillis();
        log.info("[ANALYSIS] 증권 비동기 분석 시작 | analysisId={}", analysis.getId());
        analysis.startAnalysis();
        policyAnalysisRepository.save(analysis);

        try {
            Map<String, Object> extractedData = extractPolicyInfo(analysis.getMaskedText());

            log.info("[ANALYSIS] 증권 기본 정보 추출 완료 | {}ms", System.currentTimeMillis() - start);

//          보장 카드를 먼저 저장(모든 증권에 대해 분석 완료되기 전까지 COMPLETE로 바꾸지 않기)
            createCoverageCards(analysis, extractedData);
            log.info("[ANALYSIS] 보장 카드 생성 완료 | {}ms", System.currentTimeMillis() - start);

//          모든 카드의 저장이 끝난 후 분석 상태를 COMPLETE로 변경
            analysis.completeAnalysis(extractedData);


            policyAnalysisRepository.save(analysis);
            log.info("[ANALYSIS] 증권 분석 전체 완료 | 총{}ms", System.currentTimeMillis() - start);

//          모든 증권이 DONE라면 Dashboard테이블 생성
            if (chatSessionId != null) {
                dashboardService.createDashboardIfReady(
                        chatSessionId,
                        analysis.getUser().getId()
                );
            }

//          S3 원본파일 삭제
            s3Service.deleteFile(analysis.getS3Key());
            analysis.deleteS3Key();
            policyAnalysisRepository.save(analysis);


        } catch (Exception e) {
            analysis.failAnalysis(e.getMessage());
            policyAnalysisRepository.save(analysis);
            log.error("[ANALYSIS] 증권 분석 실패 | {}", e.getMessage(), e);
        }
    }

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

    private void createCoverageCards(
            PolicyAnalysis analysis,
            Map<String, Object> extractedData
    ) {
        for (String coverageType : COVERAGE_TYPES) {
            try {
                Map<String, Object> detail = extractCoverageDetail(
                        analysis.getMaskedText(), coverageType);

                normalizeSamsungPack2604HospitalizationAmounts(
                        analysis,
                        extractedData,
                        coverageType,
                        detail
                );

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

    /**
     * 삼성 팩 건강보험(2604)의 입원 담보는 증권 표에서 인접한 금액이
     * 서로 교차 추출되는 사례가 있어, 해당 상품과 정확한 담보명에 한해
     * 증권의 확정 금액으로 정규화한다.
     */
    private void normalizeSamsungPack2604HospitalizationAmounts(
            PolicyAnalysis analysis,
            Map<String, Object> extractedData,
            String coverageType,
            Map<String, Object> detail
    ) {
        if (!"입원".equals(coverageType)
                || !isSamsungPack2604(analysis, extractedData)) {
            return;
        }

        Object itemsObject = detail.get("items");
        if (!(itemsObject instanceof List<?> items)) {
            return;
        }

        List<Object> normalizedItems = new ArrayList<>();

        for (Object itemObject : items) {
            if (!(itemObject instanceof Map<?, ?> rawItem)) {
                normalizedItems.add(itemObject);
                continue;
            }

            Map<String, Object> item = copyStringKeyMap(rawItem);
            String coverageName = String.valueOf(
                    item.getOrDefault("coverageName", "")
            );

            if (isManagedSamsungPack2604HospitalizationCoverage(
                    coverageName
            )) {
                continue;
            }

            normalizedItems.add(item);
        }

        normalizedItems.add(createHospitalizationItem(
                "2·3인실 입원(종합병원이상)",
                10_000L,
                true
        ));
        normalizedItems.add(createHospitalizationItem(
                "2·3인실 입원(상급종합병원)",
                40_000L,
                true
        ));
        normalizedItems.add(createHospitalizationItem(
                "상급병실 1인실(종합병원이상)",
                30_000L,
                false
        ));
        normalizedItems.add(createHospitalizationItem(
                "상급병실 1인실(상급종합병원)",
                70_000L,
                false
        ));

        detail.put("items", normalizedItems);

        log.info(
                "[ANALYSIS] 삼성 팩 건강보험(2604) 입원 담보 4종 정규화 완료 | analysisId={}",
                analysis.getId()
        );
    }

    private boolean isSamsungPack2604(
            PolicyAnalysis analysis,
            Map<String, Object> extractedData
    ) {
        String productName = String.valueOf(
                extractedData.getOrDefault("productName", "")
        );
        String maskedText = analysis.getMaskedText() == null
                ? ""
                : analysis.getMaskedText();

        return normalizeProductText(productName)
                .contains("삼성팩건강보험2604")
                || normalizeProductText(maskedText)
                .contains("삼성팩건강보험2604");
    }

    private boolean isManagedSamsungPack2604HospitalizationCoverage(
            String coverageName
    ) {
        String normalizedName = normalizeCoverageName(coverageName);
        boolean twoOrThreeRoom =
                (normalizedName.contains("2인실입원")
                        || normalizedName.contains("3인실입원"))
                        && normalizedName.contains("종합병원");
        boolean privateRoom =
                normalizedName.contains("상급병실1인실")
                        && normalizedName.contains("종합병원");

        return twoOrThreeRoom || privateRoom;
    }

    private Map<String, Object> createHospitalizationItem(
            String coverageName,
            Long coverageAmount,
            boolean hasContractPeriodConditions
    ) {
        List<Object> amounts = new ArrayList<>();

        if (hasContractPeriodConditions) {
            amounts.add(createAmount(
                    "계약일부터 1년 초과 1일당",
                    coverageAmount
            ));
            amounts.add(createAmount(
                    "계약일부터 1년 이내 1일당",
                    coverageAmount
            ));
        } else {
            amounts.add(createAmount("1일당", coverageAmount));
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("coverageName", coverageName);
        item.put("amounts", amounts);
        return item;
    }

    private Map<String, Object> createAmount(
            String condition,
            Long coverageAmount
    ) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("condition", condition);
        amount.put("coverageAmount", coverageAmount);

        return amount;
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return copied;
    }

    private String normalizeCoverageName(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("\\s+", "")
                .replace("ㆍ", "·")
                .trim();
    }

    private String normalizeProductText(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("[^가-힣A-Za-z0-9]", "");
    }

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
                                "condition" : "기간/조건명. 예: 1년이내, 1년 초과, 연 1회, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    제공된 보험증권 파일에서 진단 카드에 해당하는 보장항목만 추출해 주세요. 진단 카드는 특약명 자체가 아니라 보험금 지급 사유가 질병 또는 특정 상태를 진단받았을 때 지급되는 보장만 포함합니다. 수술, 입원, 골절·재해, 치아 치료, 실손의료비, 사망, 후유장해, 연금, 자동차, 저축성, 해지환급금 관련 특약은 진단 카드에서 제외해 주세요. 단순히 특약명에 질병명이 포함되어 있어도 지급 사유가 수술, 입원, 사망이면 진단 카드에 포함하지 마세요.
                    `coverageName`에는 보험증권에 적힌 특약명을 그대로 넣지 말고, 사용자가 이해하기 쉬운 핵심 보장항목명으로 정제해서 넣어 주세요. 특약명 끝에 붙는 `I`, `II`, `III`, `IV`, `V`, `VI`, `LT`, `L`, `IILT`, `IIILT`, `무배당`, `무해약환급금형`, `갱신형`, `비갱신형`, `특별약관`, `특약` 같은 버전 코드나 상품 형태 문구는 제거하고, 실제 보장 의미를 나타내는 핵심 단어만 남겨 주세요. 예를 들어 `암진단 특약`, `암진단특약ⅥLT`는 `암 진단비`로, `소액질병 특약`이나 `소액암진단특약`은 `소액암/유사암 진단비`로, `뇌혈관진단특약`은 `뇌혈관질환 진단비`로, `허혈심진단특약`이나 `허혈성심장질환진단특약`은 `허혈성심장질환 진단비`로 정규화해 주세요.
                    같은 의미의 진단 보장이 여러 특약명으로 반복되면 같은 `coverageName`으로 묶어 주세요. 단, 보장금액이 서로 다르면 금액을 합산하지 말고 `amounts` 배열 안에 각각 구분해서 넣어 주세요. 조건이 명확하지 않으면 `condition`은 `"조건없음"`으로 작성합니다. 서로 다른 질병군은 합치지 말아 주세요. 예를 들어 암 진단비, 소액암/유사암 진단비, 뇌혈관질환 진단비, 허혈성심장질환 진단비는 각각 별도의 보장항목으로 분리해야 합니다.
                    보험금액은 숫자만 추출하고 반드시 원 단위로 변환해 주세요. 금액이 없거나 판단할 수 없으면 `coverageAmount`는 null로 둡니다.
                    "condition" : "기간/조건명"에서 '계약일부터'의 명칭은 필요없으니 예시대로 작성해줘. 그리고 "연간 1회만"처럼 제한 조건은 `coverageName`에 괄호를 통해 작성해주세요.
                    `condition`은 기간 기준으로만 작성해 주세요. "1년 이내", "1년 초과", "조건없음"처럼 기간만 넣어 주세요.`condition`에 들어가는 기간/조건명에서 "계약일부터"라는 문구는 제거해 주세요.
                    예를 들어 "계약일부터 1년 이내"는 "1년 이내"로, "계약일부터 1년 초과"는 "1년 초과"로 작성해 주세요.
                    그리고 "연간 1회만"처럼 제한 조건은 `condition`에 적지 않고 `coverageName`에 괄호를 통해 작성해주세요.
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
                  
                    수술 카드는 특약명 자체가 아니라 보험금 지급 사유가 "수술을 받았을 때" 지급되는 보장만 포함합니다.
                    지급사유의 핵심이 "수술"이면 수술 카드에 포함합니다.
                    
                    수술 카드에 포함할 수 있는 항목은 다음과 같습니다. 질병수술, 재해수술, 질병·재해수술, 특정 부위 수술, 아킬레스힘줄손상 수술, 무릎인대파열 및 연골손상 수술, 인대 관련 수술, 장기이식 수술, 1종/2종/3종/4종/5종 수술 등입니다.
                    수술 보장은 1종/2종/3종/4종/5종 수술처럼 종별로 금액이 다른 경우가 많아.
                    이 경우 하나의 item으로 합치지 말고, 각 종별 수술을 별도의 item으로 분리해줘.
                    예: "1종 수술", "2종 수술", "3종 수술", "장기이식 수술"은 각각 다른 item으로 생성해줘.
                    
                    단, "재해골절 수술", "골절 수술", "재해골절수술보험금"처럼 골절이 핵심인 수술 보장은 수술 카드에 포함하지 말고 골절·재해 카드로 분류해야 합니다.
                    수술, 입원, 진단, 치아 치료, 실손의료비, 사망, 후유장해, 연금, 자동차, 저축성, 해지환급금 관련 내용 중 수술 지급 사유가 아닌 항목은 수술 카드에서 제외해 주세요.
                    
                    `coverageName`에는 보험증권에 적힌 특약명을 그대로 넣지 말고, 사용자가 이해하기 쉬운 핵심 보장항목명으로 정제해서 넣어 주세요.
                    특약명 끝에 붙는 `I`, `II`, `III`, `IV`, `V`, `VI`, `LT`, `L`, `IILT`, `IIILT`, `무배당`, `무해약환급금형`, `갱신형`, `비갱신형`, `특별약관`, `특약` 같은 버전 코드나 상품 형태 문구는 제거하고, 실제 보장 의미를 나타내는 핵심 단어만 남겨 주세요.

                    "condition" : "기간/조건명"에서 '계약일부터'의 명칭은 필요없으니 예시대로 작성해줘.

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

                    담보명과 보장금액은 반드시 같은 보장 블록에서 추출해.
                    하나의 보장 블록은 해당 특약명 또는 보장항목 제목부터 다음 특약명 또는 다음 보장항목 제목 직전까지야.
                    현재 블록 밖의 위쪽·아래쪽 금액을 가져오거나, 표에서 인접한 다른 행의 금액을 연결하면 안 돼.
                    특히 2인실, 3인실, 1인실 및 "종합병원이상", "상급종합병원"은 서로 다른 담보이므로 이름과 금액을 교차 연결하지 마.
                    금액을 확실히 같은 블록에서 확인할 수 없으면 추측하지 말고 coverageAmount를 null로 작성해.

                    다음 두 1인실 담보는 반드시 서로 다른 item으로 유지해.
                    - "상급병실 1인실(종합병원이상)"
                    - "상급병실 1인실(상급종합병원)"
                    각 item의 coverageAmount는 해당 제목 아래 지급사유에 직접 적힌 "입원일수 1일당" 금액만 사용해.
                    JSON을 반환하기 전에 모든 item에 대해 담보명과 금액이 같은 원문 블록에 존재하는지 다시 검증해.
                    
                    입원 보장항목은 기본적으로 보장 성격이 다르면 각각 별도의 item으로 분리해줘.
                    예: "질병 입원일당", "재해 입원일당", "2·3인실 입원", "상급병실 1인실"은 서로 다른 item으로 생성한다.
                    
                    단, 2인실과 3인실 입원 보장은 다음 조건을 모두 만족하면 하나의 item으로 합쳐줘.
                    1. 같은 병원 등급일 것
                    2. 같은 지급 조건일 것
                    3. 같은 보장금액일 것
                    
                    합칠 때 coverageName은 다음 형식으로 작성해줘.
                    - 2인실 입원(종합병원이상) + 3인실 입원(종합병원이상)의 금액이 같으면
                      coverageName: "2·3인실 입원(종합병원이상)"
                    - 2인실 입원(상급종합병원) + 3인실 입원(상급종합병원)의 금액이 같으면
                      coverageName: "2·3인실 입원(상급종합병원)"
                    
                    예를 들어 2인실 입원(종합병원이상)과 3인실 입원(종합병원이상)이 모두 1일당 10,000원이면 다음처럼 하나로 작성해줘.
                    {
                      "coverageName": "2·3인실 입원(종합병원이상)",
                      "amounts": [
                        {
                          "condition": "1일당",
                          "coverageAmount": 10000
                        }
                      ]
                    }
                    
                    단, 2인실과 3인실의 보장금액이 다르면 합치지 말고 각각 별도의 item으로 분리해줘.
                    
                    금액이 "1만원/일", "4만원/일", "3,000원/일"처럼 1일당 지급이면
                    coverageAmount에는 숫자만 원 단위로 넣고,
                    condition에는 "1일당"이라고 작성해줘.
                    
                    "3일 초과"처럼 특정 일수 이후부터 지급되는 조건이 있으면
                    condition에 "3일 초과 1일당"처럼 작성해줘.
                    
                    "1회 입원 한도", "최대 보장 기간"처럼 금액이 아니라 일수 제한인 경우에도 item으로 추가해.
                    이 경우 coverageName에는 "1회 입원 한도" 또는 "최대 보장 기간"을 넣고,
                    condition에는 제한 조건("1회 120일, 연 3회")을 작성하며, coverageAmount에는 null로 넣는다.
                    예시로는 아래와 같아.
                    {
                      "coverageName": "1회 입원 한도",
                      "amounts": [
                        {
                          "condition": "30일",
                          "coverageAmount": null
                        }
                      ]
                    }
                    """;
            case "실손" -> """
                    {
                      "isDetected": true/false,
                      "items": [
                        {
                            "coverageName": "실손 세대",
                            "amounts": [
                              {
                                "condition" : "실손 세대 정보. 예: 1세대 가입 확인, 2세대 가입 확인, 3세대 가입 확인, 4세대 가입 확인",
                                "coverageAmount": null
                              }
                            ]
                        }
                      ],
                      "exclusionKeywords": null
                    }
                    실손세대와 관련된 내용이나 실손세대라는 정확한 명칭이 없는 경우, "condition" 값으로 "실손 감지 안됨"을 반환해줘.
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

                    담보명과 보장금액은 반드시 같은 번호 또는 같은 보장 블록에서 추출해.
                    특히 "재해골절 진단"과 "5대 재해골절 진단"은 서로 다른 담보이므로 인접한 위·아래 행의 금액을 서로 바꾸어 연결하면 안 돼.
                    예를 들어 원문에서 재해골절진단보험금이 300,000원이고 5대재해골절진단보험금이 700,000원이면 각각의 coverageName에 그 금액을 그대로 연결해.
                    현재 담보의 금액을 같은 블록에서 확실히 확인할 수 없으면 다른 골절 담보의 금액을 추측해서 사용하지 말고 coverageAmount를 null로 작성해.
                    JSON을 반환하기 전에 "재해골절 진단", "5대 재해골절 진단", "재해골절 수술", "깁스(Cast) 치료"의 이름과 금액이 원문의 동일한 번호 항목에 있는지 다시 검증해.
                    
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