package com.codit.be_boda.auth;

import com.codit.be_boda.auth.dto.KakaoLoginResult;
import com.codit.be_boda.auth.service.KakaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.codit.be_boda.user.domain.User;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.codit.be_boda.auth.dto.LoginUser;
import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class KakaoController {

    private final KakaoService kakaoService;

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    @GetMapping("/kakao/login")
    public String kakaoLogin() {
        String encodedRedirectUri = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

        String kakaoLoginUrl = "https://kauth.kakao.com/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + encodedRedirectUri;

        return "redirect:" + kakaoLoginUrl;
    }

    @GetMapping("/callback")
    public String callback(@RequestParam String code, HttpSession session) {

        KakaoLoginResult result = kakaoService.loginOrCreateUser(code);
        User user = result.getUser();

        LoginUser loginUser = new LoginUser(
                user.getId(),
                user.getKakaoId(),
                user.getNickname()
        );

        session.setAttribute("loginUser", loginUser);
        session.setAttribute("kakaoAccessToken", result.getAccessToken());

        return "redirect:http://localhost:5173/oauth/callback/kakao";
    }

    @GetMapping("/me")
    @ResponseBody
    public Map<String, Object> me(HttpSession session) {
        LoginUser loginUser = (LoginUser) session.getAttribute("loginUser");

        Map<String, Object> response = new HashMap<>();

        if (loginUser == null) {
            response.put("loggedIn", false);
            response.put("message", "로그인하지 않은 사용자입니다.");
            response.put("user", null);
            return response;
        }

        response.put("loggedIn", true);
        response.put("message", "로그인된 사용자입니다.");
        response.put("user", loginUser);

        return response;
    }



    @GetMapping("/logout")
    @ResponseBody
    public String logout(HttpSession session) {
        String accessToken = (String) session.getAttribute("kakaoAccessToken");

        try {
            if (accessToken != null) {
                kakaoService.logout(accessToken);
            }
        } finally {
            session.invalidate();
        }
        return "로그아웃 완료";
    }
}