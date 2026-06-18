package com.codit.be_boda.auth.dto;

import com.codit.be_boda.user.domain.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KakaoLoginResult {

    private User user;
    private String accessToken;
}