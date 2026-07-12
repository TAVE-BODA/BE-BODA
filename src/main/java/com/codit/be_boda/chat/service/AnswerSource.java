package com.codit.be_boda.chat.service;

import java.math.BigDecimal;

public record AnswerSource(
        Long chunkId,
        String citedText,
        BigDecimal relevanceScore
) {
}