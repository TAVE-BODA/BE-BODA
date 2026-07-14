package com.codit.be_boda.chat.repository;

import com.codit.be_boda.chat.entity.ChatMessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ChatMessageSourceRepository
        extends JpaRepository<ChatMessageSource, Long> {

    // AI 메시지에 연결된 약관 근거 조회
    // 현재 청크에 조항 정보가 없으면 가장 가까운 이전 청크의 조항 정보를 사용
    @Query(value = """
            SELECT
                cms.source_id AS sourceId,
                cms.chunk_id AS chunkId,
                cms.cited_text AS citedText,
                cms.relevance_score AS relevanceScore,

                COALESCE(
                    NULLIF(tc.section_title, ''),
                    NULLIF(previous_clause.section_title, '')
                ) AS sectionTitle,

                tc.chunk_text AS chunkText,
                tc.clause_type AS clauseType,

                COALESCE(
                    NULLIF(c.clause_no, ''),
                    NULLIF(previous_clause.clause_no, '')
                ) AS clauseNo,

                COALESCE(
                    NULLIF(c.clause_title, ''),
                    NULLIF(previous_clause.clause_title, '')
                ) AS clauseTitle,

                COALESCE(
                    NULLIF(r.rider_name, ''),
                    NULLIF(previous_clause.rider_name, '')
                ) AS riderName

            FROM chat_message_source cms

            JOIN terms_chunk tc
                ON cms.chunk_id = tc.chunk_id

            LEFT JOIN terms_clause c
                ON tc.clause_id = c.clause_id

            LEFT JOIN terms_rider r
                ON tc.rider_id = r.rider_id

            -- 현재 청크에 조항 제목이 없을 때 사용할 이전 조항 조회
            LEFT JOIN LATERAL (
                SELECT
                    previous_c.clause_no,
                    previous_c.clause_title,
                    previous_r.rider_name,
                    previous_tc.section_title
                FROM terms_chunk previous_tc

                JOIN terms_clause previous_c
                    ON previous_tc.clause_id
                        = previous_c.clause_id

                LEFT JOIN terms_rider previous_r
                    ON previous_tc.rider_id
                        = previous_r.rider_id

                WHERE previous_tc.terms_document_id
                        = tc.terms_document_id

                  AND previous_tc.chunk_index
                        < tc.chunk_index

                  -- 현재 청크에 특약 정보가 있으면 같은 특약에서만 검색
                  AND (
                        tc.rider_id IS NULL
                        OR previous_tc.rider_id = tc.rider_id
                  )

                ORDER BY previous_tc.chunk_index DESC
                LIMIT 1
            ) previous_clause
                ON TRUE

            WHERE cms.message_id = :messageId

            ORDER BY
                cms.relevance_score DESC NULLS LAST,
                cms.source_id ASC
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