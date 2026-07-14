package com.codit.be_boda.chat.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

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

    // 여러 증권 ID 조회 (체팅방 생성 시 사용..)
    public List<PolicyAnalysisInfo> findInfoByAnalysisIds(List<Long> analysisIds) {
        if (analysisIds == null || analysisIds.isEmpty()) {
            return List.of();
        }

        String placeholders = analysisIds.stream()
                .map(id -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

        String sql = String.format("""
                SELECT analysis_id, user_id, analysis_status
                FROM policy_analysis
                WHERE analysis_id IN (%s)
                """, placeholders);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PolicyAnalysisInfo(
                        rs.getLong("analysis_id"),
                        rs.getLong("user_id"),
                        rs.getString("analysis_status")
                ),
                analysisIds.toArray()
        );
    }

    // 증권 분석 결과에서 보험 시작일 조회
    public Optional<LocalDate> findInsuranceStartDateByAnalysisId(
            Long analysisId
    ) {
        if (analysisId == null) {
            return Optional.empty();
        }

        String sql = """
            SELECT extracted_data ->> 'insuranceStartDate'
            FROM policy_analysis
            WHERE analysis_id = ?
            """;

        try {
            String insuranceStartDate =
                    jdbcTemplate.queryForObject(
                            sql,
                            String.class,
                            analysisId
                    );

            if (insuranceStartDate == null
                    || insuranceStartDate.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(
                    LocalDate.parse(
                            insuranceStartDate.trim()
                    )
            );
        } catch (EmptyResultDataAccessException
                 | DateTimeParseException e) {
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