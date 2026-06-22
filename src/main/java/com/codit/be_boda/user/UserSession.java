package com.codit.be_boda.user;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// [현재] UUID 쿠키 기반
// [카카오 연동 후] userId → kakaoId(Long)로 교체,
// 컨트롤러에서 @AuthenticationPrincipal로 현재 유저 획득 가능.

@Data
public class UserSession {

    private String userId;          // 현재: UUID | 카카오 후: kakaoId
    private LocalDateTime createdAt;

    private AnalysisState policyState = AnalysisState.NONE;
    private AnalysisState termsState  = AnalysisState.NONE;

    private String policyText;      // 마스킹된 증권 텍스트
    private String termsText;       // 마스킹된 약관 텍스트

    private InsuranceCondition condition;
    private List<ChatMessage> chatHistory = new ArrayList<>();
    private DashboardResult dashboardResult;

    public enum AnalysisState { NONE, ANALYZING, DONE, ERROR }

    @Data
    public static class InsuranceCondition {
        private String treatmentType;
        private String hospitalUsage;
        private String treatmentDate;   // 선택
        private String estimatedCost;   // 선택
    }

    @Data
    public static class ChatMessage {
        private String role;            // "user" | "assistant"
        private String content;
        private String evidence;        // 근거 약관 텍스트
        private LocalDateTime timestamp;
    }
}
