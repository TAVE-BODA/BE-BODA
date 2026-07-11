package com.codit.be_boda.auth;

import com.codit.be_boda.auth.dto.KakaoLoginResult;
import com.codit.be_boda.auth.service.KakaoService;
import com.codit.be_boda.auth.dto.LoginUser;
import com.codit.be_boda.user.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    // 프론트 URL 하드코딩 (환경변수 없이 관리)
    private static final String FRONT_URL_LOCAL = "http://localhost:5173";
    private static final String FRONT_URL_PROD  = "https://fe-boda.vercel.app";

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
    public String callback(@RequestParam String code,
                           HttpSession session,
                           HttpServletRequest httpRequest) {
        KakaoLoginResult result = kakaoService.loginOrCreateUser(code);
        User user = result.getUser();

        LoginUser loginUser = new LoginUser(
                user.getId(),
                user.getKakaoId(),
                user.getNickname(),
                user.getProfileImageUrl()
        );

        session.setAttribute("loginUser", loginUser);
        session.setAttribute("kakaoAccessToken", result.getAccessToken());

        // Referer 헤더로 로컬/배포 구분
        String origin = (String) session.getAttribute("loginOrigin");
        String frontUrl = (origin != null && origin.contains("localhost"))
                ? FRONT_URL_LOCAL
                : FRONT_URL_PROD;

        //return "redirect:" + frontUrl + "/oauth/callback/kakao";
        return "redirect:" + frontUrl + "/home";
    }

    // 카카오 로그인 시작 시 origin 저장 (로컬/배포 구분용)
    @GetMapping("/kakao/login/init")
    @ResponseBody
    public Map<String, Object> initLogin(HttpServletRequest httpRequest,
                                         HttpSession session) {
        String origin = httpRequest.getHeader("Origin");
        if (origin != null) {
            session.setAttribute("loginOrigin", origin);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("loginUrl", "/kakao/login");
        return response;
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
    public Map<String, Object> logout(HttpSession session) {
        String accessToken = (String) session.getAttribute("kakaoAccessToken");

        try {
            if (accessToken != null) {
                kakaoService.logout(accessToken);
            }
        } finally {
            session.invalidate();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "로그아웃 완료");
        return response;
    }
}
