package com.codit.be_boda.chat.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// policy_analysis 전체를 가져오지 않고, 필요한 값만 조회
@Repository
public class PolicyAnalysisQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PolicyAnalysisQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PolicyAnalysisInfo> findInfoByAnalysisId(Long analysisId) {
        String sql = """
                SELECT analysis_id, user_id, analysis_status
                FROM policy_analysis
                WHERE analysis_id = ?
                """;

        try {
            PolicyAnalysisInfo result = jdbcTemplate.queryForObject(
                    sql,
                    (rs, rowNum) -> new PolicyAnalysisInfo(
                            rs.getLong("analysis_id"),
                            rs.getLong("user_id"),
                            rs.getString("analysis_status")
                    ),
                    analysisId
            );

            return Optional.ofNullable(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record PolicyAnalysisInfo(
            Long analysisId,
            Long userId,
            String analysisStatus
    ) {
    }
}