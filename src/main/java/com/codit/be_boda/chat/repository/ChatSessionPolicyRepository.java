package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionPolicyRepository extends JpaRepository<ChatSessionPolicy, Long> {

    // 채팅방에 연결된 모든 증권 ID 조회 후 list 로 반환
    List<ChatSessionPolicy> findByChatSessionId(Long chatSessionId);

    // 분석이 끝난 analysisId로 chatSessionId를 찾아서 대시보드를 자동으로 생성
    Optional<ChatSessionPolicy> findByAnalysisId(Long analysisId);

    // 채팅방-증권 연결 삭제 (삭제 기능 시 사용)
    void deleteByChatSessionId(Long chatSessionId);
    void deleteByAnalysisId(Long analysisId);
}
