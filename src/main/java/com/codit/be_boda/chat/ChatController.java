package com.codit.be_boda.chat;

import com.codit.be_boda.user.SessionStore;
import com.codit.be_boda.user.UserSession;
import com.codit.be_boda.user.UserSession.InsuranceCondition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// 카카오 연동 후 @CookieValue("sid")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final SessionStore sessionStore;
    private final ChatService chatService;

    public ChatController(SessionStore sessionStore, ChatService chatService) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
    }

    //보험 조건 저장
    @PostMapping("/condition")
    public ResponseEntity<Object> condition(@CookieValue("sid") String sid,
                                            @RequestBody Map<String, String> body) {
        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");

        InsuranceCondition cond = new InsuranceCondition();
        cond.setTreatmentType(body.get("treatmentType"));
        cond.setHospitalUsage(body.get("hospitalUsage"));
        cond.setTreatmentDate(body.get("treatmentDate"));
        cond.setEstimatedCost(body.get("estimatedCost"));
        session.setCondition(cond);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    //챗봇 질문
    @PostMapping("/message")
    public ResponseEntity<Object> message(@CookieValue("sid") String sid,
                                          @RequestBody Map<String, String> body) {
        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");

        String question = body.get("message");
        String chipType = body.get("chipType");
        if (question == null || question.isBlank()) return bad("질문을 입력해주세요.");

        chatService.addHistory(session, "user", question, null);

        ChatService.AnswerResult result = chipType != null
            ? chatService.chipAnswer(session, chipType)
            : chatService.chat(session, question);

        chatService.addHistory(session, "assistant", result.answer(), result.evidence());

        return ResponseEntity.ok(Map.of(
            "answer",       result.answer(),
            "evidence",     result.evidence() != null ? result.evidence() : "",
            "usedFallback", result.usedFallback()
        ));
    }

    // 이미 받았던 상담 불러오기
    @GetMapping("/history")
    public ResponseEntity<Object> history(@CookieValue("sid") String sid) {
        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");
        return ResponseEntity.ok(session.getChatHistory());
    }

    private ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
