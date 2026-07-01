package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

// chat_session 저장, 조회 담당
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
}