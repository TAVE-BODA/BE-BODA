package com.codit.be_boda.auth.service;

import com.codit.be_boda.auth.dto.KakaoLoginResult;
import com.codit.be_boda.auth.dto.KakaoTokenResponse;
import com.codit.be_boda.auth.dto.KakaoUserResponse;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class KakaoService {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.client-secret}")
    private String clientSecret;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private final RestClient restClient = RestClient.create();
    private final UserRepository userRepository;

    public KakaoTokenResponse getAccessToken(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri", redirectUri);
        body.add("code", code);

        return restClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(KakaoTokenResponse.class);
    }

    public KakaoUserResponse getKakaoUserInfo(String accessToken) {
        return restClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(KakaoUserResponse.class);
    }

    @Transactional
    public KakaoLoginResult loginOrCreateUser(String code) {
        KakaoTokenResponse tokenResponse = getAccessToken(code);

        KakaoUserResponse kakaoUserResponse =
                getKakaoUserInfo(tokenResponse.getAccessToken());

        Long kakaoId = kakaoUserResponse.getId();
        String nickname = kakaoUserResponse.getNickname();
        String profileImageUrl = kakaoUserResponse.getProfileImageUrl();

        User user = userRepository.findByKakaoId(kakaoId)
                .map(existingUser -> {
                    existingUser.updateProfile(nickname, profileImageUrl);
                    return existingUser;
                })
                .orElseGet(() -> userRepository.save(
                        User.createKakaoUser(kakaoId, nickname, profileImageUrl)
                ));
        return new KakaoLoginResult(user, tokenResponse.getAccessToken());
    }

    public void logout(String accessToken) {
        restClient.post()
                .uri("https://kapi.kakao.com/v1/user/logout")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toBodilessEntity();
    }
}
