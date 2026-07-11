package com.codit.be_boda.chat.dto.response;

import com.codit.be_boda.chat.entity.ChatSession;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

// 채팅방 생성 후 프론트로 돌려주는 값
@Getter
@Builder
public class ChatSessionResponse {

    private Long chatSessionId;
    private Long userId;
    private List<Long> analysisIds;     // 연결된 증권 ID 목록 (초기엔 빈 리스트)
    private Long termsDocumentId;
    private String sessionTitle;
    private LocalDateTime createdAt;

    // 세션 생성 직후 (증권 미연결 상태)
    public static ChatSessionResponse from(ChatSession chatSession) {
        return ChatSessionResponse.builder()
                .chatSessionId(chatSession.getChatSessionId())
                .userId(chatSession.getUserId())
                .analysisIds(List.of())
                .termsDocumentId(chatSession.getTermsDocumentId())
                .sessionTitle(chatSession.getSessionTitle())
                .createdAt(chatSession.getCreatedAt())
                .build();
    }

    // 증권 연결 후
    public static ChatSessionResponse from(ChatSession chatSession, List<Long> analysisIds) {
        return ChatSessionResponse.builder()
                .chatSessionId(chatSession.getChatSessionId())
                .userId(chatSession.getUserId())
                .analysisIds(analysisIds)
                .termsDocumentId(chatSession.getTermsDocumentId())
                .sessionTitle(chatSession.getSessionTitle())
                .createdAt(chatSession.getCreatedAt())
                .build();
    }
}
