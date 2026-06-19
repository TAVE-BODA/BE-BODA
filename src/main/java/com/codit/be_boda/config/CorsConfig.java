package com.codit.be_boda.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

	@Bean
	public CorsFilter corsFilter() {
		CorsConfiguration config = new CorsConfiguration();

		// 허용할 도메인
		config.setAllowedOrigins(List.of(
			"http://localhost:3000",
			"http://127.0.0.1:3000",
			"http://127.0.0.1:3001"
			//프론트 배포시 서버 추가
		));

		//허용할 HTTP 메서드
		config.setAllowedMethods(List.of(
			"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
		));

		// 허용할 헤더
		config.setAllowedHeaders(List.of("*"));

		// 인증 정보 포함 여부 (Authorization 헤더, 쿠키 등)
		config.setAllowCredentials(true);

		// preflight 캐시 시간 (초)
		config.setMaxAge(3600L);

		// 응답 헤더 노출 (프론트에서 읽을 수 있도록)
		config.setExposedHeaders(List.of(
			"Authorization",
			"Content-Disposition"
		));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);

		return new CorsFilter(source);
	}
}

