package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.upload.service.PdfExtractService;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

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
    private final PdfExtractService pdfExtractService;

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

//    여러 증권을 모두 저장하고 채팅방에 연결 -> 마지막에 비동기 분석 시작
    @Transactional
    public List<Long> createAndStartMultipleAnalyses(
            User user,
            List<MultipartFile> files,
            Long chatSessionId
    ) {
        // 생성된 여러 PolicyAnalysis를 임시로 보관
        List<PolicyAnalysis> analyses = new ArrayList<>();

//      1. 모든 pdf를 추출하고 PolicyAnalysis에 저장
        for (MultipartFile file : files) {
            PdfExtractService.ExtractResult extracted =
                    pdfExtractService.extract(file);

            if (!extracted.success()) {
                throw new IllegalArgumentException(
                        "PDF 추출 실패: "
                                + file.getOriginalFilename()
                                + " / "
                                + extracted.errorMessage()
                );
            }

            PolicyAnalysis analysis = PolicyAnalysis.builder()
                    .user(user)
                    .originalFileName(file.getOriginalFilename())
                    .s3Key(null)
                    .isOcr(extracted.isOcr())
                    .maskedText(extracted.text())
                    .build();

            policyAnalysisRepository.save(analysis);
            analyses.add(analysis);

            log.info(
                    "[ANALYSIS] 다중 업로드 증권 저장 | analysisId={}, fileName={}",
                    analysis.getId(),
                    file.getOriginalFilename()
            );
        }

//      2. 한번에 올린 증권을 하나의 같은 채팅방에 연결
        for (PolicyAnalysis analysis : analyses) {
            chatSessionPolicyRepository.save(
                    new ChatSessionPolicy(
                            chatSessionId,
                            analysis.getId()
                    )
            );
        }

//      3. 모든 증권을 등록한 후 비동기 분석
        for (PolicyAnalysis analysis : analyses) {
            asyncPolicyAnalysisService.analyzeAsync(
                    analysis,
                    chatSessionId
            );
        }

//      4. 생성된 analysisId 목록 반환
        return analyses.stream()
                .map(PolicyAnalysis::getId)
                .toList();
    }

    // 삭제 순서(FK 역방향): 보장카드 -> 채팅방-증권 연결 -> S3 원본 -> 증권 분석
    @Transactional
    public void deletePolicy(Long analysisId, Long userId) {
        PolicyAnalysis analysis = policyAnalysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("증권을 찾을 수 없어요."));

        if (!analysis.getUser().getId().equals(userId)) {
            throw new SecurityException("본인의 증권만 삭제할 수 있어요.");
        }

        // coverage_item (보장카드)
        List<CoverageItem> items =
                coverageItemRepository.findByPolicyAnalysisOrderByCoverageType(analysis);
        coverageItemRepository.deleteAll(items);

        // chat_session_policy 연결 해제 (analysisId 기준 dangling 방지)
        chatSessionPolicyRepository.deleteByAnalysisId(analysisId);
        s3Service.deleteFile(analysis.getS3Key());
        policyAnalysisRepository.delete(analysis);

        log.info("[ANALYSIS] 증권 삭제 완료 | analysisId={} | 보장카드 {}건", analysisId, items.size());
    }
}
