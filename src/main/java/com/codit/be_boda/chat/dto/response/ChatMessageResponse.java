package com.codit.be_boda.chat.dto.response;

import com.codit.be_boda.chat.entity.ChatMessage;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 말풍선 하나 응답
@Getter
@Builder
public class ChatMessageResponse {

    private Long messageId;
    private SenderType senderType;
    private QuestionType questionType;
    private String messageContent;
    private Boolean usedFallback;
    private String disclaimerText;
    private Boolean hasSources;
    private LocalDateTime createdAt;

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return ChatMessageResponse.builder()
                .messageId(chatMessage.getMessageId())
                .senderType(chatMessage.getSenderType())
                .questionType(chatMessage.getQuestionType())
                .messageContent(chatMessage.getMessageContent())
                .usedFallback(chatMessage.getUsedFallback())
                .disclaimerText(chatMessage.getDisclaimerText())
                .hasSources(false) // 약관 근거 연결 시 true, false 처리
                .createdAt(chatMessage.getCreatedAt())
                .build();
    }
}