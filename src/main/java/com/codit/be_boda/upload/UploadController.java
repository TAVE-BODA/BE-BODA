package com.codit.be_boda.upload;

import com.codit.be_boda.analysis.AnalysisService;
import com.codit.be_boda.user.SessionStore;
import com.codit.be_boda.user.UserSession.AnalysisState;
import com.codit.be_boda.user.UserSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// PDF 업로드 API
// @CookieValue("sid") → @AuthenticationPrincipal OAuth2User user 로 바꾸기
@RequestMapping("/api/upload")
public class UploadController {

    private final SessionStore sessionStore;
    private final PdfExtractService pdfExtractService;
    private final AnalysisService analysisService;

    public UploadController(SessionStore sessionStore,
                            PdfExtractService pdfExtractService,
                            AnalysisService analysisService) {
        this.sessionStore = sessionStore;
        this.pdfExtractService = pdfExtractService;
        this.analysisService = analysisService;
    }

    //보험증권
    @PostMapping("/policy")
    public ResponseEntity<Object> policy(@RequestParam("file") MultipartFile file,
                                         @CookieValue(value = "sid", required = false) String sid,
                                         HttpServletResponse response) {
        UserSession session = resolveSession(sid, response);
        PdfExtractService.ExtractResult r = pdfExtractService.extract(file);
        if (!r.success())
            return ResponseEntity.badRequest().body(Map.of("error", r.errorMessage(), "code", r.errorCode()));

        session.setPolicyText(r.text());
        session.setPolicyState(AnalysisState.ANALYZING);
        analysisService.analyzePolicy(session);
        return ResponseEntity.ok(Map.of("status", "ANALYZING", "message", "증권 분석을 시작했어요!"));
    }

    // 보험약관
    @PostMapping("/terms")
    public ResponseEntity<Object> terms(@RequestParam("file") MultipartFile file,
                                        @CookieValue(value = "sid", required = false) String sid,
                                        HttpServletResponse response) {
        UserSession session = resolveSession(sid, response);
        PdfExtractService.ExtractResult r = pdfExtractService.extract(file);
        if (!r.success())
            return ResponseEntity.badRequest().body(Map.of("error", r.errorMessage(), "code", r.errorCode()));

        session.setTermsText(r.text());
        session.setTermsState(AnalysisState.ANALYZING);
        analysisService.analyzeTerms(session);
        return ResponseEntity.ok(Map.of("status", "ANALYZING", "message", "약관을 읽는 중이에요. 다른 거 하고 와도 괜찮아요 😊"));
    }

    // 분석 상태 폴링
    @GetMapping("/status")
    public ResponseEntity<Object> status(@CookieValue(value = "sid", required = false) String sid) {
        if (!sessionStore.exists(sid))
            return ResponseEntity.badRequest().body(Map.of("error", "세션 없음"));

        UserSession session = sessionStore.get(sid);
        return ResponseEntity.ok(Map.of(
            "policyState",  session.getPolicyState().name(),
            "termsState",   session.getTermsState().name(),
            "hasDashboard", session.getDashboardResult() != null
        ));
    }

    private UserSession resolveSession(String sid, HttpServletResponse response) {
        if (!sessionStore.exists(sid)) {
            sid = sessionStore.newId();
            Cookie c = new Cookie("sid", sid);
            c.setMaxAge(60 * 60 * 24 * 7);
            c.setPath("/");
            c.setHttpOnly(true);
            response.addCookie(c);
        }
        return sessionStore.getOrCreate(sid);
    }
}
