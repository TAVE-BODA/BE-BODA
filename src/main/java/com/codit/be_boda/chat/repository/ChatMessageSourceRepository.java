package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatMessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ChatMessageSourceRepository extends JpaRepository<ChatMessageSource, Long> {

    // 채팅방 삭제 시 사용 (chat_message 보다 먼저 지워야 FK 위반이 나지 않음)
    void deleteByMessageIdIn(List<Long> messageIds);

    // 여러 메시지 중 약관 근거가 저장된 메시지 ID만 한 번에 조회
    @Query(value = """
            SELECT DISTINCT cms.message_id
            FROM chat_message_source cms
            WHERE cms.message_id IN (:messageIds)
            """, nativeQuery = true)
    List<Long> findMessageIdsWithSources(
            @Param("messageIds") List<Long> messageIds
    );

    // AI 메시지에 연결된 약관 근거 조회
    @Query(value = """
            SELECT
                cms.source_id AS sourceId,
                cms.chunk_id AS chunkId,
                cms.cited_text AS citedText,
                cms.relevance_score AS relevanceScore,
                tc.section_title AS sectionTitle,
                tc.chunk_text AS chunkText,
                tc.clause_type AS clauseType,
                c.clause_no AS clauseNo,
                c.clause_title AS clauseTitle,
                r.rider_name AS riderName
            FROM chat_message_source cms
            JOIN terms_chunk tc ON cms.chunk_id = tc.chunk_id
            LEFT JOIN terms_clause c ON tc.clause_id = c.clause_id
            LEFT JOIN terms_rider r ON tc.rider_id = r.rider_id
            WHERE cms.message_id = :messageId
            ORDER BY cms.relevance_score DESC NULLS LAST, cms.source_id ASC
            """, nativeQuery = true)
    List<MessageSourceInfo> findSourceInfosByMessageId(
            @Param("messageId") Long messageId
    );

    interface MessageSourceInfo {
        Long getSourceId();

        Long getChunkId();

        String getCitedText();

        BigDecimal getRelevanceScore();

        String getSectionTitle();

        String getChunkText();

        String getClauseType();

        String getClauseNo();

        String getClauseTitle();

        String getRiderName();
    }
}