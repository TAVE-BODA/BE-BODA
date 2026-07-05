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
        private String type;            // 진단 / 수술 / 입원 / 골절재해 / 실손 / 치아
        private String insurerTag;
        private String coverageAmount;
        private List<String> exclusions;
        private String evidenceText;
        private boolean termsUploaded;
    }
}
