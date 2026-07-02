package com.codit.be_boda.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// 전체 보장 카드 보여줄 때 사용
@Repository
public class CoverageItemQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoverageItemQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CoverageItemInfo> findByAnalysisId(Long analysisId) {
        String sql = """
                SELECT coverage_id,
                       coverage_type,
                       is_detected,
                       exclusion_keywords,
                       evidence_text,
                       detail
                FROM coverage_item
                WHERE analysis_id = ?
                ORDER BY coverage_id ASC
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new CoverageItemInfo(
                        rs.getLong("coverage_id"),
                        rs.getString("coverage_type"),
                        rs.getBoolean("is_detected"),
                        rs.getString("exclusion_keywords"),
                        rs.getString("evidence_text"),
                        String.valueOf(rs.getObject("detail"))
                ),
                analysisId
        );
    }

    public record CoverageItemInfo(
            Long coverageId,
            String coverageType,
            Boolean isDetected,
            String exclusionKeywords,
            String evidenceText,
            String detail
    ) {
    }
}