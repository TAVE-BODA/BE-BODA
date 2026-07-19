package com.codit.be_boda.upload.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// NOT_INSURANCE_DOCUMENT: 보험 서류인지 키워드 기반 판별
// TERMS_MISMATCH: 약관이 채팅방에 연결된 증권과 같은 보험사인지 대조
//판별은 키워드 기반(동기 업로드 경로라 LLM 호출 지연/비용 없이 즉시 판정).
//오탐으로 정상 업로드를 막지 않도록, 판단 근거가 부족하면 통과시키는 방향으로 설계.
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentValidator {

    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final PolicyAnalysisRepository policyAnalysisRepository;

    // 보험증권 판별 키워드
    private static final List<String> POLICY_KEYWORDS = List.of(
            "보험", "증권", "계약자", "피보험자", "보험료", "보험금",
            "보장", "특약", "가입금액", "보험기간", "수익자", "만기");
    private static final int POLICY_MIN_HITS = 3;

    // 보험약관 판별 키워드
    private static final List<String> TERMS_KEYWORDS = List.of(
            "약관", "보험금", "지급사유", "보험료", "계약자", "피보험자",
            "면책", "보상하지", "보험기간", "특별약관", "보통약관");
    private static final int TERMS_MIN_HITS = 3;

    // 보험사명 뒤 법인/업종 표기 (핵심 브랜드 토큰 추출용)
    // 예) "삼성화재해상보험(주)" → "삼성"
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "(주식회사|㈜|화재해상보험|해상보험|손해보험|생명보험|화재|생명|손보|해상|보험)+$");

    // 보험증권 문서인지
    public boolean isPolicyDocument(String text) {
        int hits = countHits(text, POLICY_KEYWORDS);
        log.info("[VALIDATE] 증권 키워드 매칭 | hits={}/{}", hits, POLICY_MIN_HITS);
        return hits >= POLICY_MIN_HITS;
    }

    // 보험약관 문서인지
    public boolean isTermsDocument(String text) {
        int hits = countHits(text, TERMS_KEYWORDS);
        log.info("[VALIDATE] 약관 키워드 매칭 | hits={}/{}", hits, TERMS_MIN_HITS);
        return hits >= TERMS_MIN_HITS;
    }

    // 업로드한 약관이 채팅방에 연결된 증권과 같은 보험사인지 확인
    // 판단 불가(세션 없음 / 연결 증권 없음 / 보험사명 미추출)면 true 반환하여 통과시킨다.
    // 증권 분석이 비동기라 업로드 시점에 companyName이 아직 없을 수 있기 때문.
    public boolean matchesLinkedPolicy(String termsText, Long chatSessionId) {
        if (chatSessionId == null || termsText == null || termsText.isBlank()) {
            return true;
        }

        List<ChatSessionPolicy> links =
                chatSessionPolicyRepository.findByChatSessionId(chatSessionId);
        if (links.isEmpty()) {
            return true; // 연결된 증권 없음 → 대조 불가
        }

        List<String> companyNames = new ArrayList<>();
        for (ChatSessionPolicy link : links) {
            policyAnalysisRepository.findById(link.getAnalysisId())
                    .map(PolicyAnalysis::getCompanyName)
                    .filter(name -> name != null && !name.isBlank())
                    .ifPresent(companyNames::add);
        }

        if (companyNames.isEmpty()) {
            log.info("[VALIDATE] 증권 보험사명 미확보 → 약관 대조 생략 | sessionId={}", chatSessionId);
            return true; // 아직 분석 전이면 판단 불가 → 통과
        }

        for (String company : companyNames) {
            if (containsCompany(termsText, company)) {
                log.info("[VALIDATE] 약관-증권 보험사 일치 | company={}", company);
                return true;
            }
        }

        log.info("[VALIDATE] 약관-증권 보험사 불일치 | 증권 보험사={}", companyNames);
        return false;
    }

    // 약관 본문에 증권 보험사명(또는 브랜드 토큰)이 등장하는지
    private boolean containsCompany(String text, String companyName) {
        String normalizedText = text.replaceAll("\\s+", "");
        String normalized = companyName.replaceAll("[\\s()（）]", "");

        if (normalized.length() >= 2 && normalizedText.contains(normalized)) {
            return true;
        }

        // "삼성화재해상보험" → "삼성" 처럼 업종 표기를 떼고 브랜드 토큰으로 재확인
        String brand = COMPANY_SUFFIX.matcher(normalized).replaceAll("");
        return brand.length() >= 2 && normalizedText.contains(brand);
    }

    private int countHits(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }
}
