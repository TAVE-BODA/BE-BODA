package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
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
            analysis.completeAnalysis(extractedData);
            policyAnalysisRepository.save(analysis);
            log.info("[ANALYSIS] 증권 기본 정보 추출 완료 | {}ms", System.currentTimeMillis() - start);

            createCoverageCards(analysis);
            log.info("[ANALYSIS] 보장 카드 생성 완료 | {}ms", System.currentTimeMillis() - start);

            if (chatSessionId != null) {
                chatSessionPolicyRepository.save(
                        new ChatSessionPolicy(chatSessionId, analysis.getId())
                );
                log.info("[ANALYSIS] 채팅방 연결 완료 | chatSessionId={} analysisId={}",
                        chatSessionId, analysis.getId());
            }

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
                    `coverageName`에는 보험증권에 적힌 특약명을 그대로 넣지 말고, 사용자가 이해하기 쉬운 핵심 보장항목명으로 정제해서 넣어 주세요. 특약명 끝에 붙는 `I`, `II`, `III`, `IV`, `V`, `VI`, `LT`, `L`, `IILT`, `IIILT`, `무배당`, `무해약환급금형`, `갱신형`, `비갱신형`, `특별약관`, `특약` 같은 버전 코드나 상품 형태 문구는 제거하고, 실제 보장 의미를 나타내는 핵심 단어만 남겨 주세요.
                    같은 의미의 진단 보장이 여러 특약명으로 반복되면 같은 `coverageName`으로 묶어 주세요. 단, 보장금액이 서로 다르면 금액을 합산하지 말고 `amounts` 배열 안에 각각 구분해서 넣어 주세요.
                    """;
            case "수술" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "수술 관련 보장항목명",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예시: 1년 이내, 1년 초과, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    수술 카드는 보험금 지급 사유가 수술을 받았을 때 지급되는 보장만 포함합니다.
                    """;
            case "입원" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "입원 관련 보장항목명",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 1일당, 3일 초과 1일당, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
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
                    """;
            case "골절재해" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "골절·재해 관련 보장항목명",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 보험기간 전체, 조건없음, 약관이 필요해요",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
                        "exclusionKeywords": "면책 키워드 요약"
                    }
                    """;
            case "치아" -> """
                    {
                        "isDetected": true/false,
                        "items": [
                          {
                            "coverageName": "치아 치료 관련 보장항목명",
                            "amounts": [
                              {
                                "condition" : "기간/조건명. 예: 2년 이내, 2년 초과, 조건없음",
                                "coverageAmount": 보장금액(숫자만, 원 단위, 없으면 null)
                              }
                            ]
                          }
                        ],
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
