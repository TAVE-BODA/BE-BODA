package com.codit.be_boda.upload.dto;

// 증권 다중 업로드 시 파일 1건의 처리 결과
// status: ANALYZING(접수 성공) / FAILED(거절)
// 실패한 파일만 code, error가 채워진다.
public record PolicyUploadResultResponse(
        String fileName,
        String status,
        Long analysisId,
        String code,
        String error
) {
    public static PolicyUploadResultResponse accepted(String fileName, Long analysisId) {
        return new PolicyUploadResultResponse(fileName, "ANALYZING", analysisId, null, null);
    }

    public static PolicyUploadResultResponse failed(String fileName, String code, String error) {
        return new PolicyUploadResultResponse(fileName, "FAILED", null, code, error);
    }
}
