package com.codit.be_boda.dashboard.dto;

import com.codit.be_boda.dashboard.domain.Dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DashboardResponse(
        String sessionId,
        Long userId,
        String insuredName,
        LocalDate analysisCompletedAt,
        List<Long> analysisIds,
        List<String> companyNames,
        List<CoverageSummaryDto> coverageSummaries,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DashboardResponse from(Dashboard dashboard) {
        return new DashboardResponse(
                dashboard.getSessionId(),
                dashboard.getUser().getId(),
                dashboard.getInsuredName(),
                dashboard.getAnalysisCompletedAt(),
                dashboard.getAnalysisIds(),
                dashboard.getCompanyNames(),
                dashboard.getCoverageSummaries(),
                dashboard.getCreatedAt(),
                dashboard.getUpdatedAt()
        );
    }
}