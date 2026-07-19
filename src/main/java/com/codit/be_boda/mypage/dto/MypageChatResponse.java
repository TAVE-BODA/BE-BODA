package com.codit.be_boda.mypage.dto;

import java.time.LocalDate;
import java.util.List;

// 보험사 카테고리 안의 채팅방 1건
// "채팅창 선택" 드롭다운에 노출되는 항목 (예: "2026.06.10 대화")
public record MypageChatResponse(
        Long chatSessionId,
        String title,                 // "2026.06.10 대화"
        LocalDate createdDate,        // 세션 생성일 (정렬 기준)
        List<Long> analysisIds,       // 이 채팅방에 연결된 증권 (연결 순서)
        Long termsDocumentId,         // 연결된 약관 (없으면 null)
        boolean termsUploaded,        // 약관 연결 여부
        boolean conditionCompleted,   // 설문(개인 상황) 입력 완료 여부
        boolean dashboardAvailable    // 대시카드 생성 여부
) {
}
