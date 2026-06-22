package com.codit.be_boda.analysis;

import com.codit.be_boda.user.SessionStore;
import com.codit.be_boda.user.UserSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final SessionStore sessionStore;

    public DashboardController(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @GetMapping
    public ResponseEntity<Object> get(@CookieValue("sid") String sid) {
        UserSession session = sessionStore.get(sid);
        if (session == null)
            return ResponseEntity.badRequest().body(Map.of("error", "세션 없음"));
        if (session.getDashboardResult() == null)
            return ResponseEntity.ok(Map.of("ready", false, "message", "아직 분석 중이에요."));
        return ResponseEntity.ok(Map.of("ready", true, "data", session.getDashboardResult()));
    }
}
