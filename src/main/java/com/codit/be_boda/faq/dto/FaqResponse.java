package com.codit.be_boda.faq.dto;

public record FaqResponse(
        Long id,
        String category,
        String question,
        String answer
) {
}