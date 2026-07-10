package com.codit.be_boda.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

public record DashboardSummaryResponse(
        String insuredName,
        LocalDate analysisCompletedAt,
        String companyName,
        List<DashboardCoverageSummaryResponse> coverageSummaries
) {
}