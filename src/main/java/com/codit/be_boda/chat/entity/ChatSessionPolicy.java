package com.codit.be_boda.chat.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

//중간 테이블
@Getter
@Entity
@Table(name = "chat_session_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSessionPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_session_id", nullable = false)
    private Long chatSessionId;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatSessionPolicy(Long chatSessionId, Long analysisId) {
        this.chatSessionId = chatSessionId;
        this.analysisId = analysisId;
        this.createdAt = LocalDateTime.now();
    }
}
