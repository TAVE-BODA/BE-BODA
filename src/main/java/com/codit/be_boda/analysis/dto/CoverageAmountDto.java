package com.codit.be_boda.analysis.dto;

public record CoverageAmountDto(
        String condition,
        Long coverageAmount
) {
}