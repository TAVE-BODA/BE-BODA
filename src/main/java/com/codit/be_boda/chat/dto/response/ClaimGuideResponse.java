package com.codit.be_boda.chat.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClaimGuideResponse {

    private String claimStatus;
    private String summary;
    private List<String> reasons;
    private List<String> cautions;
}