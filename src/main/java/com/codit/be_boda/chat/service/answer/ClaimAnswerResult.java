package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;

public record ClaimAnswerResult(
        String messageContent,
        ClaimGuideResponse claimGuide
) {
}