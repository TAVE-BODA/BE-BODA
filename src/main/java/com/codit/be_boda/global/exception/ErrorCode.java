package com.codit.be_boda.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// 에러 종류를 미리 정해두는 파일
@Getter
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다."),

    ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "존재하지 않는 증권 분석 ID입니다."),
    ANALYSIS_NOT_DONE(HttpStatus.CONFLICT, "ANALYSIS_NOT_DONE", "아직 증권 분석이 완료되지 않았습니다."),

    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND", "존재하지 않는 채팅방입니다."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status; // 실제 HTTP 상태 코드
    private final String code; // 프론트가 구분할 에러 코드
    private final String message; // 사용자/프론트에게 보여줄 메시지

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}