package com.codit.be_boda.chat.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AmountGuideResponse {

    private Boolean calculationAvailable;
    private List<EstimatedItem> estimatedItems;
    private List<String> cautions;

    // 결과 전체 약관 근거
    private Boolean hasSources;
    private List<Long> sourceChunkIds;

    @Getter
    @Builder
    public static class EstimatedItem {

        private String coverageName;
        private String amountText;
        private String reason;

        // 개별 금액 카드의 약관 근거
        private Boolean hasSources;
        private List<Long> sourceChunkIds;
    }
}