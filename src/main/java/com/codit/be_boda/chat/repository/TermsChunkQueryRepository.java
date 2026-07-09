package com.codit.be_boda.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class TermsChunkQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public TermsChunkQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TermsChunkInfo> findByTermsDocumentIdAndKeywords(
            Long termsDocumentId,
            List<String> keywords,
            int limit
    ) {
        if (termsDocumentId == null || keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        List<String> validKeywords = keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .distinct()
                .toList();

        if (validKeywords.isEmpty()) {
            return List.of();
        }

        StringBuilder conditionBuilder = new StringBuilder();

        for (int i = 0; i < validKeywords.size(); i++) {
            if (i > 0) {
                conditionBuilder.append(" OR ");
            }

            conditionBuilder.append("""
                    (
                        tc.chunk_text LIKE ?
                        OR COALESCE(tc.section_title, '') LIKE ?
                        OR COALESCE(tc.clause_type, '') LIKE ?
                        OR COALESCE(cl.clause_title, '') LIKE ?
                    )
                    """);
        }

        String sql = """
                SELECT tc.chunk_id,
                       tc.terms_document_id,
                       tc.clause_id,
                       cl.clause_no,
                       cl.clause_title,
                       tc.section_title,
                       tc.chunk_text
                FROM terms_chunk tc
                LEFT JOIN terms_clause cl
                    ON tc.clause_id = cl.clause_id
                WHERE tc.terms_document_id = ?
                  AND (
                """ + conditionBuilder + """
                  )
                ORDER BY
                    CASE
                        WHEN COALESCE(cl.clause_title, tc.section_title, '') LIKE '%보험금의 청구%' THEN 1
                        WHEN tc.chunk_text LIKE '%사고보험금 청구서류%' THEN 2
                        WHEN tc.chunk_text LIKE '%청구서류%' THEN 3
                        WHEN tc.chunk_text LIKE '%사고증명서%' THEN 4
                        ELSE 9
                    END,
                    tc.chunk_index ASC
                LIMIT ?
                """;

        List<Object> params = new ArrayList<>();
        params.add(termsDocumentId);

        for (String keyword : validKeywords) {
            String pattern = "%" + keyword + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        params.add(limit);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TermsChunkInfo(
                        rs.getLong("chunk_id"),
                        rs.getLong("terms_document_id"),
                        rs.getObject("clause_id") == null ? null : rs.getLong("clause_id"),
                        rs.getString("clause_no"),
                        rs.getString("clause_title"),
                        rs.getString("section_title"),
                        rs.getString("chunk_text")
                ),
                params.toArray()
        );
    }

    public record TermsChunkInfo(
            Long chunkId,
            Long termsDocumentId,
            Long clauseId,
            String clauseNo,
            String clauseTitle,
            String sectionTitle,
            String chunkText
    ) {
    }
}