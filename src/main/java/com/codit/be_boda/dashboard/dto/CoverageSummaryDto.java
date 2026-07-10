package com.codit.be_boda.dashboard.dto;

import java.util.List;

public record CoverageSummaryDto(
        String coverageType,
        Long minAmount,
        Long maxAmount,
        String unit,
        List<String> companyNames
) {
}