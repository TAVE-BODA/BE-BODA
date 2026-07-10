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

    @Column(name = "terms_document_id")
    private Long termsDocumentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_title")
    private String sessionTitle;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public ChatSession(Long userId, Long termsDocumentId, String sessionTitle) {
        this.userId = userId;
        this.termsDocumentId = termsDocumentId;
        this.sessionTitle = sessionTitle;
        this.createdAt = LocalDateTime.now();
    }

    // 첫 번째 질문 시 system_prompt 저장
    public void saveSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    // system_prompt가 없으면 첫 번째 질문
    public boolean isFirstMessage() {
        return this.systemPrompt == null;
    }

    // 약관 연결 (파싱 완료 후 자동 연결)
    public void updateTermsDocument(Long termsDocumentId) {
        this.termsDocumentId = termsDocumentId;
    }
}