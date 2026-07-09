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

    @Getter
    @Builder
    public static class EstimatedItem {
        private String coverageName;
        private String amountText;
        private String reason;
    }
}