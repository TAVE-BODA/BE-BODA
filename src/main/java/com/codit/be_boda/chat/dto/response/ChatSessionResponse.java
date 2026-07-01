package com.codit.be_boda.chat.dto.response;

import com.codit.be_boda.chat.entity.ChatSession;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 채팅방 생성 후 프론트로 돌려주는 값
@Getter
@Builder
public class ChatSessionResponse {

    private Long chatSessionId;
    private Long userId;
    private Long analysisId;
    private Long termsDocumentId;
    private String sessionTitle;
    private LocalDateTime createdAt;

    public static ChatSessionResponse from(ChatSession chatSession) {
        return ChatSessionResponse.builder()
                .chatSessionId(chatSession.getChatSessionId())
                .userId(chatSession.getUserId())
                .analysisId(chatSession.getAnalysisId())
                .termsDocumentId(chatSession.getTermsDocumentId())
                .sessionTitle(chatSession.getSessionTitle())
                .createdAt(chatSession.getCreatedAt())
                .build();
    }
}