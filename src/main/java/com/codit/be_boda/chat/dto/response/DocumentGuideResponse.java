package com.codit.be_boda.chat.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DocumentGuideResponse {

    private List<DocumentItem> documents;
    private List<EvidenceItem> evidences;
    private Boolean evidenceAvailable;
    private String notice;

    @Getter
    @Builder
    public static class DocumentItem {
        private String name;
        private String description;
        private Boolean required;
    }

    @Getter
    @Builder
    public static class EvidenceItem {
        private Long chunkId;
        private String title;
    }
}