package com.codit.be_boda.chat.service;

import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.dto.response.ClaimGuideResponse;
import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;

import java.util.LinkedHashSet;
import java.util.Set;

import java.util.List;

public record ChatAnswerResult(
        String messageContent,
        ClaimGuideResponse claimGuide,
        AmountGuideResponse amountGuide,
        DocumentGuideResponse documentGuide,
        boolean hasSources,
        List<AnswerSource> sources
) {
    // 카드 DTO 없는 기본 답변 결과 생성
    public static ChatAnswerResult text(String messageContent) {
        return new ChatAnswerResult(
                messageContent,
                null,
                null,
                null,
                false,
                List.of()
        );
    }

    // 청구 가능 여부 카드 답변 결과 생성
    public static ChatAnswerResult claim(String messageContent, ClaimGuideResponse claimGuide) {
        return new ChatAnswerResult(
                messageContent,
                claimGuide,
                null,
                null,
                false,
                List.of()
        );
    }

    public static ChatAnswerResult claim(
            String messageContent,
            ClaimGuideResponse claimGuide,
            boolean hasSources,
            List<AnswerSource> sources
    ) {
        return new ChatAnswerResult(
                messageContent,
                claimGuide,
                null,
                null,
                hasSources,
                sources == null ? List.of() : sources
        );
    }

    // 예상 보험금 카드 답변 결과 생성
    public static ChatAnswerResult amount(String messageContent, AmountGuideResponse amountGuide) {
        return new ChatAnswerResult(
                messageContent,
                null,
                amountGuide,
                null,
                false,
                List.of()
        );
    }

    // 예상 보험금 카드 + 약관 근거 답변 결과 생성
    public static ChatAnswerResult amount(
            String messageContent,
            AmountGuideResponse amountGuide,
            boolean hasSources,
            List<AnswerSource> sources
    ) {
        return new ChatAnswerResult(
                messageContent,
                null,
                amountGuide,
                null,
                hasSources,
                sources == null ? List.of() : sources
        );
    }

    // 필요 서류 카드 답변 결과 생성
    public static ChatAnswerResult documents(
            String messageContent,
            DocumentGuideResponse documentGuide,
            boolean hasSources,
            List<AnswerSource> sources
    ) {
        return new ChatAnswerResult(
                messageContent,
                null,
                null,
                documentGuide,
                hasSources,
                sources == null ? List.of() : sources
        );
    }
}