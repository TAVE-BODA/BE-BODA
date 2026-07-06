package com.codit.be_boda.dashboard.dto;

import java.util.List;

public record DashboardResponse(
        Long analysisId,
        String analysisStatus,

        String companyName,
        String insuranceStartDate,
        String insuranceEndDate,

        List<CoverageCardResponse> coverages
) {
}
