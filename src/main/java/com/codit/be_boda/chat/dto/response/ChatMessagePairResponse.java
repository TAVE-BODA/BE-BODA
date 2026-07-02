package com.codit.be_boda.chat.dto.response;

import lombok.Builder;
import lombok.Getter;

// 사용자가 질문을 보냈을 때, 유저 + AI 말풍선 한 번에 내려주는 응답
@Getter
@Builder
public class ChatMessagePairResponse {

    private Long chatSessionId;
    private ChatMessageResponse userMessage;
    private ChatMessageResponse aiMessage;

    public static ChatMessagePairResponse of(
            Long chatSessionId,
            ChatMessageResponse userMessage,
            ChatMessageResponse aiMessage
    ) {
        return ChatMessagePairResponse.builder()
                .chatSessionId(chatSessionId)
                .userMessage(userMessage)
                .aiMessage(aiMessage)
                .build();
    }
}