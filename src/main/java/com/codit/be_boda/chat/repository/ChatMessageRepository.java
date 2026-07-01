package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// chat_message 저장, 조회 담당
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByChatSessionIdOrderByCreatedAtAsc(Long chatSessionId);
}