package com.codit.be_boda.chat.dto.response;

import com.codit.be_boda.chat.entity.ChatMessage;
import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
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
    private DocumentGuideResponse documentGuide;
    private ClaimGuideResponse claimGuide;
    private AmountGuideResponse amountGuide;

    // 카드 DTO 없는 기본 메시지 응답 생성
    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return from(chatMessage, null, null, null, false);
    }

    // 카드 DTO 포함 메시지 응답 생성
    public static ChatMessageResponse from(
            ChatMessage chatMessage,
            ClaimGuideResponse claimGuide,
            AmountGuideResponse amountGuide,
            DocumentGuideResponse documentGuide,
            Boolean hasSources
    ) {
        return ChatMessageResponse.builder()
                .messageId(chatMessage.getMessageId())
                .senderType(chatMessage.getSenderType())
                .questionType(chatMessage.getQuestionType())
                .messageContent(chatMessage.getMessageContent())
                .usedFallback(chatMessage.getUsedFallback())
                .disclaimerText(chatMessage.getDisclaimerText())
                .hasSources(hasSources)
                .claimGuide(claimGuide)
                .amountGuide(amountGuide)
                .documentGuide(documentGuide)
                .createdAt(chatMessage.getCreatedAt())
                .build();
    }
}