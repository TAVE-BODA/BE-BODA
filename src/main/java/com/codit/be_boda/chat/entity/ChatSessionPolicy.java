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

    // 한 채팅방에 연결 가능한 증권 최대 개수 (기획: 증권 1~3개, 약관 0~1개)
    public static final int MAX_PER_SESSION = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//  대시보드의 pk가 됨
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
