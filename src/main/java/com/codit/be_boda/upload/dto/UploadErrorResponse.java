package com.codit.be_boda.upload.dto;

// 파일 업로드 예외 응답 공통 포맷
public record UploadErrorResponse(String code, String error) {
}
