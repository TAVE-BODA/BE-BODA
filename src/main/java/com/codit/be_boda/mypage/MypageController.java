package com.codit.be_boda.mypage;

import com.codit.be_boda.auth.dto.LoginUser;
import com.codit.be_boda.mypage.dto.MypageResponse;
import com.codit.be_boda.mypage.service.MypageService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mypage")
public class MypageController {

    private final MypageService mypageService;

    @GetMapping
    public MypageResponse getMyPage(HttpSession session) {
        LoginUser loginUser =
                (LoginUser) session.getAttribute("loginUser");

        if (loginUser == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        return mypageService.getMyPage(loginUser.id());
    }
}