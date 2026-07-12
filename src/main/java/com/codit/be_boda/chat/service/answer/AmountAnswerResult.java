package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.response.AmountGuideResponse;

public record AmountAnswerResult(
        String messageContent,
        AmountGuideResponse amountGuide
) {
}