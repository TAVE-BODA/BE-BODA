package com.codit.be_boda.analysis.dto;

import java.util.List;

public record CoverageItemDto(
        String coverageName,
        List<CoverageAmountDto> amounts
) {
}