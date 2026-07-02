package com.codit.be_boda.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "chat_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_session_id")
    private Long chatSessionId;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Column(name = "terms_document_id")
    private Long termsDocumentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_title")
    private String sessionTitle;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public ChatSession(Long analysisId, Long userId, Long termsDocumentId, String sessionTitle) {
        this.analysisId = analysisId;
        this.userId = userId;
        this.sessionTitle = sessionTitle;
        this.createdAt = LocalDateTime.now();
    }
}