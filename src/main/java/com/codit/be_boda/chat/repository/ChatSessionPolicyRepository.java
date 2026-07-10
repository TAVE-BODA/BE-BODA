package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionPolicyRepository extends JpaRepository<ChatSessionPolicy, Long> {

    // 채팅방에 연결된 모든 증권 ID 조회
    List<ChatSessionPolicy> findByChatSessionId(Long chatSessionId);

    // 채팅방-증권 연결 삭제 (삭제 기능 시 사용)
    void deleteByChatSessionId(Long chatSessionId);
    void deleteByAnalysisId(Long analysisId);
}
