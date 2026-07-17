package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// chat_session 저장, 조회 담당
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    // 약관 삭제 시 dangling 참조 정리용 (해당 약관을 연결한 세션들)
    List<ChatSession> findByTermsDocumentId(Long termsDocumentId);
}
