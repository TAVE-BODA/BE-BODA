package com.codit.be_boda.user;

import lombok.Data;
import java.util.List;

@Data
public class DashboardResult {

    private String insurerName;
    private String estimatedAmount;
    private List<CoverageCard> cards;

    @Data
    public static class CoverageCard {
        private String type;            // 진단비 / 수술비 / 입원비 / 골절재해 / 생활특수 / 치아
        private String insurerTag;
        private String coverageAmount;
        private List<String> exclusions;
        private String evidenceText;
        private boolean termsUploaded;
    }
}
