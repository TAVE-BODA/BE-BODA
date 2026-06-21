package com.codit.be_boda.auth.dto;

import java.io.Serializable;

public record LoginUser(
        Long id,
        Long kakaoId,
        String nickname,
        String profileImageUrl
) implements Serializable {
}
