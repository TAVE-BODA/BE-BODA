package com.codit.be_boda.dashboard.dto;

import com.codit.be_boda.analysis.dto.CoverageItemDto;

import java.util.List;

public record CoverageCardResponse(
        String coverageType,
        Boolean isDetected,
        List<CoverageItemDto> items,
        String exclusionKeywords
) {
}
