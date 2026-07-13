package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
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
import com.codit.be_boda.dashboard.service.DashboardService;

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
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final S3Service s3Service;
    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final AsyncPolicyAnalysisService asyncPolicyAnalysisService;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    // 보장 카드 6종
    private static final List<String> COVERAGE_TYPES =
            List.of("진단", "수술", "입원", "실손", "골절재해", "치아");

    @Transactional
    public PolicyAnalysis createAndStartAnalysis(User user, String originalFileName,
                                                 String s3Key, boolean isOcr,
                                                 String maskedText, Long chatSessionId) {
        PolicyAnalysis analysis = PolicyAnalysis.builder()
                .user(user)
                .originalFileName(originalFileName)
                .s3Key(s3Key)
                .isOcr(isOcr)
                .maskedText(maskedText)
                .build();

        policyAnalysisRepository.save(analysis);

        // 분석 시작 전에 채팅방과 analysisId 연결
        // 채팅방 연결 (코드3 플로우: 업로드 시 chatSessionId 포함 시 중간 테이블 저장)
        if (chatSessionId != null) {
            chatSessionPolicyRepository.save(
                    new ChatSessionPolicy(chatSessionId, analysis.getId())
            );

        }

        log.info("[ANALYSIS] 증권 분석 레코드 생성 | analysisId={}", analysis.getId());

        // 별도 클래스 호출 → Spring AOP 프록시 경유 → @Async 정상 동작
        asyncPolicyAnalysisService.analyzeAsync(analysis, chatSessionId);
        return analysis;
    }
}
