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

    /**
     * CHIP_CLAIM 전용 근거 검색.
     * 지급사유·정의·제외 조항을 우선하고,
     * 청구서류·목차·선지급 특약 청크는 제외한다.
     */
    public List<TermsChunkInfo> findClaimByTermsDocumentIdAndKeywords(
            Long termsDocumentId,
            List<String> keywords,
            int limit
    ) {
        if (termsDocumentId == null
                || keywords == null
                || keywords.isEmpty()) {
            return List.of();
        }

        List<String> validKeywords = keywords.stream()
                .filter(keyword ->
                        keyword != null
                                && !keyword.isBlank()
                )
                .distinct()
                .toList();

        if (validKeywords.isEmpty()) {
            return List.of();
        }

        StringBuilder keywordCondition =
                new StringBuilder();

        for (int index = 0;
             index < validKeywords.size();
             index++) {

            if (index > 0) {
                keywordCondition.append(" OR ");
            }

            keywordCondition.append("""
                    (
                        tc.chunk_text ILIKE ?
                        OR COALESCE(tc.section_title, '') ILIKE ?
                        OR COALESCE(cl.clause_title, '') ILIKE ?
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
                """ + keywordCondition + """
                  )

                  -- 청구 가능 여부의 판단 근거만 허용
                  AND (
                        tc.chunk_text ILIKE '%지급사유%'
                        OR tc.chunk_text ILIKE '%지급금액%'
                        OR tc.chunk_text ILIKE '%지급하지 않%'
                        OR tc.chunk_text ILIKE '%제외%'
                        OR tc.chunk_text ILIKE '%정의%'
                        OR COALESCE(cl.clause_title, '') ILIKE '%지급사유%'
                        OR COALESCE(cl.clause_title, '') ILIKE '%지급하지 않%'
                        OR COALESCE(cl.clause_title, '') ILIKE '%정의%'
                        OR COALESCE(tc.section_title, '') ILIKE '%지급사유%'
                        OR COALESCE(tc.section_title, '') ILIKE '%정의%'
                        OR COALESCE(tc.clause_type, '') IN (
                            'PAYMENT_REASON',
                            'EXCLUSION',
                            'DEFINITION',
                            'DETAIL_RULE',
                            'PERIOD'
                        )
                  )

                  -- CHIP_DOCUMENTS 전용 청크 제외
                  AND tc.chunk_text NOT ILIKE '%사고보험금 청구서류%'
                  AND tc.chunk_text NOT ILIKE '%청구서(회사양식)%'
                  AND tc.chunk_text NOT ILIKE '%구비서류%'
                  AND tc.chunk_text NOT ILIKE '%사고증명서%'
                  AND COALESCE(cl.clause_title, '')
                        NOT ILIKE '%보험금%청구%'
                  AND COALESCE(tc.section_title, '')
                        NOT ILIKE '%보험금%청구%'

                  -- 공통 안내·목차·선지급 특약 제외
                  AND tc.chunk_text NOT ILIKE '%선지급 치료비%'
                  AND tc.chunk_text NOT ILIKE '%보험계약의 일반사항%'
                  AND tc.chunk_text NOT ILIKE '%해약환급금%'
                  AND NOT (
                        COALESCE(tc.clause_type, '') = 'TABLE'
                        AND tc.clause_id IS NULL
                  )

                ORDER BY
                    CASE
                        WHEN COALESCE(
                                cl.clause_title,
                                tc.section_title,
                                ''
                             ) ILIKE '%보험금%지급사유%'
                            THEN 1

                        WHEN tc.chunk_text ILIKE '%지급사유%'
                             AND tc.chunk_text ILIKE '%지급금액%'
                            THEN 2

                        WHEN COALESCE(tc.clause_type, '') = 'PAYMENT_REASON'
                            THEN 3

                        WHEN COALESCE(
                                cl.clause_title,
                                tc.section_title,
                                ''
                             ) ILIKE '%정의%'
                            THEN 4

                        WHEN tc.chunk_text ILIKE '%지급하지 않%'
                             OR tc.chunk_text ILIKE '%제외%'
                            THEN 5

                        ELSE 9
                    END,
                    tc.chunk_index ASC
                LIMIT ?
                """;

        List<Object> params =
                new ArrayList<>();

        params.add(termsDocumentId);

        for (String keyword : validKeywords) {
            String pattern =
                    "%" + keyword + "%";

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
                        rs.getObject("clause_id") == null
                                ? null
                                : rs.getLong("clause_id"),
                        rs.getString("clause_no"),
                        rs.getString("clause_title"),
                        rs.getString("section_title"),
                        rs.getString("chunk_text")
                ),
                params.toArray()
        );
    }

    public List<TermsChunkInfo> findAmountByTermsDocumentIdAndConcepts(
            Long termsDocumentId,
            List<String> coverageConcepts,
            List<String> conditionConcepts,
            int limit
    ) {
        if (termsDocumentId == null
                || coverageConcepts == null
                || coverageConcepts.isEmpty()) {
            return List.of();
        }

        List<String> validCoverageConcepts =
                normalizeConcepts(coverageConcepts);

        List<String> validConditionConcepts =
                normalizeConcepts(conditionConcepts);

        if (validCoverageConcepts.isEmpty()) {
            return List.of();
        }

        String coverageCondition =
                buildConceptCondition(
                        validCoverageConcepts.size()
                );

        String conditionCondition =
                validConditionConcepts.isEmpty()
                        ? ""
                        : """
                      AND (
                      """ + buildConceptCondition(
                        validConditionConcepts.size()
                ) + """
                      )
                      """;

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

              -- 실제 계산된 보장명에서 추출한 개념을 모두 포함
              AND (
            """ + coverageCondition + """
              )
            """ + conditionCondition + """

              -- 예상 보험금 지급 근거에 해당하는 청크
              AND (
                    tc.chunk_text ILIKE '%지급금액%'
                    OR tc.chunk_text ILIKE '%보험가입금액%'
                    OR tc.chunk_text ILIKE '%지급기준%'
                    OR tc.chunk_text ILIKE '%보험금%지급사유%'
                    OR COALESCE(tc.section_title, '') ILIKE '%지급금액%'
                    OR COALESCE(tc.section_title, '') ILIKE '%지급기준%'
                    OR COALESCE(cl.clause_title, '') ILIKE '%지급금액%'
                    OR COALESCE(cl.clause_title, '') ILIKE '%지급기준%'
                    OR COALESCE(cl.clause_title, '') ILIKE '%보험금%지급사유%'
              )

              -- 청구서류 및 청구 방법 조항 제외
              AND COALESCE(cl.clause_title, '')
                    NOT ILIKE '%보험금%청구%'
              AND COALESCE(tc.section_title, '')
                    NOT ILIKE '%보험금%청구%'
              AND tc.chunk_text
                    NOT ILIKE '%청구서(회사양식)%'
              AND tc.chunk_text
                    NOT ILIKE '%사고보험금 청구서류%'
              AND tc.chunk_text
                    NOT ILIKE '%구비서류%'
              AND tc.chunk_text
                    NOT ILIKE '%사고증명서%'

              -- 조항에 연결되지 않은 단순 목차 제외
              AND NOT (
                    COALESCE(tc.clause_type, '') = 'TABLE'
                    AND tc.clause_id IS NULL
              )

              -- 선지급 치료비 특약 제외
              AND COALESCE(cl.clause_title, '')
                    NOT ILIKE '%선지급 치료비%'
              AND COALESCE(tc.section_title, '')
                    NOT ILIKE '%선지급 치료비%'
              AND tc.chunk_text
                    NOT ILIKE '%선지급 치료비%'

            ORDER BY
                CASE
                    WHEN COALESCE(
                            cl.clause_title,
                            tc.section_title,
                            ''
                         ) ILIKE '%지급사유%지급금액%'
                        THEN 1

                    WHEN tc.chunk_text ILIKE '%지급금액%'
                         AND tc.chunk_text ILIKE '%보험가입금액%'
                        THEN 2

                    WHEN COALESCE(
                            cl.clause_title,
                            tc.section_title,
                            ''
                         ) ILIKE '%보험금%지급사유%'
                        THEN 3

                    WHEN COALESCE(tc.clause_type, '')
                            = 'PAYMENT_REASON'
                        THEN 4

                    ELSE 9
                END,
                tc.chunk_index ASC
            LIMIT ?
            """;

        List<Object> params =
                new ArrayList<>();

        params.add(termsDocumentId);

        addConceptParams(
                params,
                validCoverageConcepts
        );

        addConceptParams(
                params,
                validConditionConcepts
        );

        params.add(limit);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TermsChunkInfo(
                        rs.getLong("chunk_id"),
                        rs.getLong("terms_document_id"),
                        rs.getObject("clause_id") == null
                                ? null
                                : rs.getLong("clause_id"),
                        rs.getString("clause_no"),
                        rs.getString("clause_title"),
                        rs.getString("section_title"),
                        rs.getString("chunk_text")
                ),
                params.toArray()
        );
    }

    private List<String> normalizeConcepts(
            List<String> concepts
    ) {
        if (concepts == null) {
            return List.of();
        }

        return concepts.stream()
                .filter(concept ->
                        concept != null
                                && !concept.isBlank()
                )
                .map(this::normalizeSearchText)
                .filter(concept -> !concept.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeSearchText(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "");
    }

    private String buildConceptCondition(
            int conceptCount
    ) {
        StringBuilder builder =
                new StringBuilder();

        for (int i = 0; i < conceptCount; i++) {
            if (i > 0) {
                builder.append(" AND ");
            }

            builder.append("""
                (
                    regexp_replace(
                        COALESCE(tc.chunk_text, ''),
                        '\\s+',
                        '',
                        'g'
                    ) ILIKE ?
                    OR regexp_replace(
                        COALESCE(tc.section_title, ''),
                        '\\s+',
                        '',
                        'g'
                    ) ILIKE ?
                    OR regexp_replace(
                        COALESCE(cl.clause_title, ''),
                        '\\s+',
                        '',
                        'g'
                    ) ILIKE ?
                )
                """);
        }

        return builder.toString();
    }

    private void addConceptParams(
            List<Object> params,
            List<String> concepts
    ) {
        for (String concept : concepts) {
            String pattern =
                    "%" + concept + "%";

            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
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