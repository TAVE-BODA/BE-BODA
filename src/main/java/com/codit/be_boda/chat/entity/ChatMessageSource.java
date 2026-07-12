package com.codit.be_boda.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_message_source")
public class ChatMessageSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "cited_text")
    private String citedText;

    @Column(name = "relevance_score")
    private BigDecimal relevanceScore;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 답변 근거 source 저장용 생성자
    public ChatMessageSource(
            Long messageId,
            Long chunkId,
            String citedText,
            BigDecimal relevanceScore
    ) {
        this.messageId = messageId;
        this.chunkId = chunkId;
        this.citedText = citedText;
        this.relevanceScore = relevanceScore;
        this.createdAt = LocalDateTime.now();
    }
}