package com.codit.be_boda.dashboard.dto;

public record DashboardCoverageSummaryResponse(
        String coverageType,
        Long coverageTotalMin,
        Long coverageTotalMax,
        String coverageUnit
) {
}