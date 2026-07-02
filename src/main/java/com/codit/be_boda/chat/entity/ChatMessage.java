package com.codit.be_boda.chat.entity;

import com.codit.be_boda.chat.type.QuestionType;
import com.codit.be_boda.chat.type.SenderType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "chat_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "chat_session_id", nullable = false)
    private Long chatSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private SenderType senderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", length = 30)
    private QuestionType questionType;

    @Column(name = "message_content", nullable = false, columnDefinition = "TEXT")
    private String messageContent;

    @Column(name = "used_fallback")
    private Boolean usedFallback;

    @Column(name = "disclaimer_text")
    private String disclaimerText;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatMessage(
            Long chatSessionId,
            SenderType senderType,
            QuestionType questionType,
            String messageContent,
            Boolean usedFallback,
            String disclaimerText
    ) {
        this.chatSessionId = chatSessionId;
        this.senderType = senderType;
        this.questionType = questionType;
        this.messageContent = messageContent;
        this.usedFallback = usedFallback;
        this.disclaimerText = disclaimerText;
        this.createdAt = LocalDateTime.now();
    }
}