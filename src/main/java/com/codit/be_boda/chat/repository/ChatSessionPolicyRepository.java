package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionPolicyRepository extends JpaRepository<ChatSessionPolicy, Long> {

    // 채팅방에 연결된 모든 증권 ID 조회 후 list 로 반환
    List<ChatSessionPolicy> findByChatSessionId(Long chatSessionId);

    // 연결된 순서(= 등록 순서)대로 조회
    // 마이페이지 보험사 카테고리는 "첫 번째로 연결된 증권"의 보험사를 기준으로 하므로 순서가 중요하다
    List<ChatSessionPolicy> findByChatSessionIdOrderByIdAsc(Long chatSessionId);

    // 하나의 증권이 여러 채팅방에서 재사용될 수 있으므로 List 로 반환한다
    // (이전 채팅의 증권을 그대로 쓰는 기능 → Optional 이면 결과가 2건 이상일 때 예외 발생)
    List<ChatSessionPolicy> findByAnalysisId(Long analysisId);

    // 채팅방-증권 연결 삭제 (삭제 기능 시 사용)
    void deleteByChatSessionId(Long chatSessionId);
    void deleteByAnalysisId(Long analysisId);
}
