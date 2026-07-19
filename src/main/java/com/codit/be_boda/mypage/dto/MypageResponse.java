package com.codit.be_boda.mypage.dto;

import com.codit.be_boda.user.domain.User;

import java.time.LocalDate;
import java.util.List;

public record MypageResponse(
        String userName,
        LocalDate firstLoginDate,
        List<MypageInsuranceResponse> insurances
) {

    public static MypageResponse of(
            User user,
            List<MypageInsuranceResponse> insurances
    ) {
        return new MypageResponse(
                user.getNickname(),
                user.getCreatedAt().toLocalDate(),
                insurances
        );
    }
}