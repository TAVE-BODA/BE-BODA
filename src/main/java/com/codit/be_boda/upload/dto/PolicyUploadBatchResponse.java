package com.codit.be_boda.upload.dto;

import java.util.List;

// 증권 다중 업로드 응답
// 파일 하나가 실패해도 요청 전체를 실패시키지 않는다.
// 프론트는 analysisIds(성공한 것들)만 폴링하면 됨
public record PolicyUploadBatchResponse(
        Long chatSessionId,
        int requestedCount,
        int acceptedCount,
        int failedCount,
        List<Long> analysisIds,
        List<PolicyUploadResultResponse> results,
        String message
) {
}
