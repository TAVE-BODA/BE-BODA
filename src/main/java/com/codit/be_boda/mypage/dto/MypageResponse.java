package com.codit.be_boda.mypage.dto;

import com.codit.be_boda.user.domain.User;

import java.time.LocalDate;

public record MypageResponse(
        String nickname,
        LocalDate firstLoginDate
) {

    public static MypageResponse from(User user) {
        return new MypageResponse(
                user.getNickname(),
                user.getCreatedAt().toLocalDate()
//              최초 로그인한 년월일만 반환
        );
    }
}