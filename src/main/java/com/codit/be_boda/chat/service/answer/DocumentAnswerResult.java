package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;

public record DocumentAnswerResult(
        String messageContent,
        DocumentGuideResponse documentGuide,
        boolean hasSources
) {
}