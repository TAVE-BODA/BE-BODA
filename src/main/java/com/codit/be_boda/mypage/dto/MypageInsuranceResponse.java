package com.codit.be_boda.mypage.dto;

public record MypageInsuranceResponse(
        Long analysisId,
        String companyName,
        boolean dashboardAvailable, // 대시보드 유무
        boolean termsUploaded, // 약관 업로드 유무
        boolean conditionCompleted,// 조건 입력 유무
        Long existingChatSessionId, //기존 채팅방Id
        boolean canCreateNewChat // 새로운 채팅방 생성 가능 여부
) {
}