package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.service.AnswerSource;

import java.util.List;

public record DocumentAnswerResult(
        String messageContent,
        DocumentGuideResponse documentGuide,
        boolean hasSources,
        List<AnswerSource> sources
) {
}