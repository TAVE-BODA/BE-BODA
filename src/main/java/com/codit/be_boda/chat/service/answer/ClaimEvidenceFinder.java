package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.type.TreatmentType;
import com.codit.be_boda.chat.service.AnswerSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ClaimEvidenceFinder {

    private static final int SOURCE_LIMIT = 6;

    private final TermsChunkQueryRepository termsChunkQueryRepository;

    public List<AnswerSource> findSources(
            Long termsDocumentId,
            ChatMessageRequest request
    ) {
        if (termsDocumentId == null) {
            return List.of();
        }

        List<String> keywords = buildKeywords(request);

        if (keywords.isEmpty()) {
            return List.of();
        }

        return termsChunkQueryRepository
                .findByTermsDocumentIdAndKeywords(
                        termsDocumentId,
                        keywords,
                        SOURCE_LIMIT
                )
                .stream()
                .map(chunk -> new AnswerSource(
                        chunk.chunkId(),
                        null,
                        null
                ))
                .toList();
    }

    private List<String> buildKeywords(
            ChatMessageRequest request
    ) {
        Set<String> keywords = new LinkedHashSet<>();

        if (request.getTreatmentTypes() != null) {
            for (TreatmentType treatmentType
                    : request.getTreatmentTypes()) {

                addTreatmentKeywords(
                        keywords,
                        treatmentType
                );
            }
        }

        // "골절", "감기"처럼 짧게 입력된 진단명도 검색에 활용
        if (request.getMessage() != null
                && !request.getMessage().isBlank()
                && request.getMessage().trim().length() <= 20) {

            keywords.add(request.getMessage().trim());
        }

        return List.copyOf(keywords);
    }

    private void addTreatmentKeywords(
            Set<String> keywords,
            TreatmentType treatmentType
    ) {
        switch (treatmentType) {
            case CAST -> {
                keywords.add("깁스");
                keywords.add("Cast");
                keywords.add("부목");
                keywords.add("깁스치료");
            }

            case SURGERY -> {
                keywords.add("수술보험금");
                keywords.add("수술급여금");
                keywords.add("질병수술");
                keywords.add("재해수술");
                keywords.add("수술");
            }

            case HOSPITALIZATION -> {
                keywords.add("입원보험금");
                keywords.add("입원급여금");
                keywords.add("입원일당");
                keywords.add("입원");
                keywords.add("병실");
            }

            case DENTAL -> {
                keywords.add("치과치료");
                keywords.add("치아치료");
                keywords.add("영구치");
                keywords.add("발치");
                keywords.add("크라운");
                keywords.add("임플란트");
            }

            case DIAGNOSIS_ONLY -> {
                keywords.add("진단보험금");
                keywords.add("진단급여금");
                keywords.add("진단비");
                keywords.add("진단");
            }

            case OUTPATIENT -> {
                keywords.add("통원");
                keywords.add("외래");
                keywords.add("실손의료");
                keywords.add("처방조제");
            }

            case DISABILITY -> {
                keywords.add("후유장해");
                keywords.add("장해보험금");
                keywords.add("장해급여금");
                keywords.add("장해분류표");
            }
        }
    }
}