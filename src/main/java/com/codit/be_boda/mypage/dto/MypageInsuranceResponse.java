package com.codit.be_boda.mypage.dto;

import java.time.LocalDate;
import java.util.List;

// 마이페이지 보험사 카테고리 카드 1건
//카테고리 판정 기준
// 1순위: 채팅방에 연결된 약관의 보험사
// 2순위: 채팅방에 첫 번째로 연결된 증권의 보험사
// 버튼/뱃지 판정은 "마지막 채팅(생성 시각이 가장 늦은 채팅)"을 기준으로 한다.
public record MypageInsuranceResponse(
        String companyKey,                 // 정규화된 보험사 키 (그룹핑용)
        String companyName,                // 표시용 보험사명
        String title,                      // 카드 제목 (대표 상품명, 없으면 "OO 보험증권")
        LocalDate registeredAt,            // 등록일 (가장 먼저 등록된 증권 기준)

        List<Long> analysisIds,            // 이 카테고리의 증권 전체
        String policyStatus,               // 대표 증권 분석 상태 (PENDING/ANALYZING/DONE/ERROR)
        boolean policyCompleted,           // 증권 분석 완료 여부

        boolean termsUploaded,             // 마지막 채팅에 약관 연결됨
        boolean conditionCompleted,        // 마지막 채팅에 설문 입력됨
        boolean dashboardAvailable,        // 대시카드 존재
        boolean canUploadTermsToContinue,  // "이어서 약관 업로드하고 채팅하러 가기" 노출 여부

        int chatCount,                     // 채팅방 수 ("채팅 2건")
        List<MypageChatResponse> chats     // 생성 시각 오름차순
) {
}
