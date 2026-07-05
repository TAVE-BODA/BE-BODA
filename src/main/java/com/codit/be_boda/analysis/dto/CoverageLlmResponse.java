package com.codit.be_boda.analysis.dto;

import java.util.List;

public record CoverageLlmResponse(
        Boolean isDetected,
        List<CoverageItemDto> items,
        String exclusionKeywords
) {
}