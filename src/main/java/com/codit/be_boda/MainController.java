package com.codit.be_boda;

import com.codit.be_boda.user.SessionStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MainController {

    private final SessionStore sessionStore;

    public MainController(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @GetMapping("/")
    public String index(@CookieValue(name = "sid", required = false) String sid,
                        HttpServletResponse response, Model model) {
        model.addAttribute("sessionId", resolveSession(sid, response));
        return "index";
    }

    @GetMapping("/dashboard")
    public String dashboard(@CookieValue(name = "sid", required = false) String sid,
                            HttpServletResponse response, Model model) {
        model.addAttribute("sessionId", resolveSession(sid, response));
        return "dashboard";
    }

    @GetMapping("/faq")
    public String faq() { return "faq"; }

    private String resolveSession(String sid, HttpServletResponse response) {
        if (!sessionStore.exists(sid)) {
            sid = sessionStore.newId();
            sessionStore.getOrCreate(sid);
            Cookie c = new Cookie("sid", sid);
            c.setMaxAge(60 * 60 * 24 * 7);
            c.setPath("/");
            c.setHttpOnly(true);
            response.addCookie(c);
        }
        return sid;
    }
}
