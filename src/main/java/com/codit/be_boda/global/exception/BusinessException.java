package com.codit.be_boda.global.exception;

import lombok.Getter;

// 직접 넘겨줄 커스텀 예외
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String customMessage;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.customMessage = errorCode.getMessage();
    }

    public BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.customMessage = customMessage;
    }
}