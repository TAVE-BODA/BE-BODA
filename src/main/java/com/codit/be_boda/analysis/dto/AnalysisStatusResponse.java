package com.codit.be_boda.analysis.dto;

public record AnalysisStatusResponse(
        Long analysisId,
        String analysisStatus,
        Long termsDocumentId,
        String parsingStatus,
        boolean hasCoverageCards
) {}
