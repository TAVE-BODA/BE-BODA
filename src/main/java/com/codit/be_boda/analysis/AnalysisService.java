package com.codit.be_boda.analysis;

import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.user.DashboardResult;
import com.codit.be_boda.user.DashboardResult.CoverageCard;
import com.codit.be_boda.user.UserSession;
import com.codit.be_boda.user.UserSession.AnalysisState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final RagService ragService;
    private final boolean mockMode;

    public AnalysisService(RagService ragService,
                           @Value("${app.mock-mode:false}") boolean mockMode) {
        this.ragService = ragService;
        this.mockMode = mockMode;
    }

    // 보험증권 분석 (비동기)
    @Async
    public void analyzePolicy(UserSession session) {
        log.info("[ANALYSIS] 증권 분석 시작 | user={}", session.getUserId());
        session.setPolicyState(AnalysisState.ANALYZING);
        try {
            if (mockMode) Thread.sleep(1000);
            // 실서비스: LLM으로 증권에서 보험사명, 피보험자, 주요 보장 추출..
            session.setPolicyState(AnalysisState.DONE);
            log.info("[ANALYSIS] 증권 분석 완료");
        } catch (Exception e) {
            session.setPolicyState(AnalysisState.ERROR);
            log.error("[ANALYSIS] 증권 분석 실패 | {}", e.getMessage());
        }
    }

    //보험약관 분석 (비동기) — RAG 인덱싱 + 대시보드 카드
    //대시보드 카드는 피그마 명세 따라서 더 세부적으로 나누기..
    //현재 대시보드 "진단비", "수술비", "입원비", "골절·재해", "생활·특수", "치아"
    //수정점 생활, 특수 제외 후 실손보험 카드로 넣기..
    @Async
    public void analyzeTerms(UserSession session) {
        long start = System.currentTimeMillis();
        log.info("[ANALYSIS] 약관 분석 시작 | user={}", session.getUserId());
        session.setTermsState(AnalysisState.ANALYZING);
        try {
            if (mockMode) {
                Thread.sleep(2000);
                session.setDashboardResult(mockDashboard());
            } else {
                ragService.indexTerms(session.getUserId(), session.getTermsText());
                session.setDashboardResult(buildDashboard(session));
            }
            session.setTermsState(AnalysisState.DONE);
            log.info("[ANALYSIS] 약관 분석 완료 | 총{}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            session.setTermsState(AnalysisState.ERROR);
            log.error("[ANALYSIS] 약관 분석 실패 | {}", e.getMessage(), e);
        }
    }

    private DashboardResult buildDashboard(UserSession session) {
        List<CoverageCard> cards = new ArrayList<>();
        for (String type : new String[]{"진단비", "수술비", "입원비", "골절·재해", "생활·특수", "치아"}) {
            List<String> chunks = ragService.search(session.getUserId(), type + " 보장 조건 금액");
            if (!chunks.isEmpty()) {
                CoverageCard card = new CoverageCard();
                card.setType(type);
                card.setTermsUploaded(true);
                card.setEvidenceText(chunks.get(0).substring(0, Math.min(200, chunks.get(0).length())));
                cards.add(card);
            }
        }
        DashboardResult r = new DashboardResult();
        r.setCards(cards);
        return r;
    }

    //목데이터.. (임의 데이터. LLM 연결 X시 해당 데이터가 나옴.)
    private DashboardResult mockDashboard() {
        DashboardResult r = new DashboardResult();
        r.setInsurerName("삼성생명");
        r.setEstimatedAmount("4,000만원");
        List<CoverageCard> cards = new ArrayList<>();
        cards.add(card("진단비",  "암: 3,000만원", List.of("선천성 질환"), "제12조 진단비 조항"));
        cards.add(card("수술비",  "1종: 100만원",  List.of("미용 수술"),   "제15조 수술비 조항"));
        cards.add(card("입원비",  "1일 5만원",     List.of("1일 이하"),    "제8조 입원일당 조항"));
        r.setCards(cards);
        return r;
    }

    //각 카드 결과물
    private CoverageCard card(String type, String amount, List<String> excl, String evidence) {
        CoverageCard c = new CoverageCard();
        c.setType(type); //보장 카드 타입
        c.setCoverageAmount(amount); // 보장 최대 얼마나 해주는지
        c.setExclusions(excl); // 병명, 사고명
        c.setEvidenceText(evidence); // 법적 명시
        c.setTermsUploaded(true);
        return c;
    }
}
