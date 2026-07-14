package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.service.AnswerSource;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ClaimEvidenceFinder {

    // 칩1 전체 근거 개수
    private static final int SOURCE_LIMIT = 6;

    // 칩2 카드 한 개당 근거 개수
    private static final int SOURCE_LIMIT_PER_ITEM = 2;

    private final TermsChunkQueryRepository termsChunkQueryRepository;

    /**
     * CHIP_CLAIM 근거 검색
     */
    public List<AnswerSource> findSources(
            Long termsDocumentId,
            ChatMessageRequest request
    ) {
        if (termsDocumentId == null
                || request == null) {
            return List.of();
        }

        List<String> keywords =
                buildClaimKeywords(request);

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

    /**
     * CHIP_AMOUNT 카드 한 개의 근거 검색
     */
    public List<AnswerSource> findAmountSourcesForItem(
            Long termsDocumentId,
            ChatMessageRequest request,
            AmountGuideResponse.EstimatedItem estimatedItem
    ) {
        if (termsDocumentId == null
                || request == null
                || estimatedItem == null) {
            return List.of();
        }

        List<String> coverageConcepts =
                buildCoverageConcepts(
                        estimatedItem.getCoverageName(),
                        request
                );

        List<String> conditionConcepts =
                buildConditionConcepts(
                        estimatedItem.getReason()
                );

        if (coverageConcepts.isEmpty()) {
            return List.of();
        }

        return termsChunkQueryRepository
                .findAmountByTermsDocumentIdAndConcepts(
                        termsDocumentId,
                        coverageConcepts,
                        conditionConcepts,
                        SOURCE_LIMIT_PER_ITEM
                )
                .stream()
                .map(chunk -> new AnswerSource(
                        chunk.chunkId(),
                        null,
                        null
                ))
                .toList();
    }

    private List<String> buildCoverageConcepts(
            String coverageName,
            ChatMessageRequest request
    ) {
        Set<String> concepts =
                new LinkedHashSet<>();

        String normalizedName =
                normalize(coverageName);

        addIfContained(concepts, normalizedName, "재해");
        addIfContained(concepts, normalizedName, "상해");
        addIfContained(concepts, normalizedName, "질병");
        addIfContained(concepts, normalizedName, "수술");
        addIfContained(concepts, normalizedName, "입원");
        addIfContained(concepts, normalizedName, "통원");
        addIfContained(concepts, normalizedName, "깁스");
        addIfContained(concepts, normalizedName, "골절");
        addIfContained(concepts, normalizedName, "치아");
        addIfContained(concepts, normalizedName, "발치");
        addIfContained(concepts, normalizedName, "임플란트");
        addIfContained(concepts, normalizedName, "크라운");
        addIfContained(concepts, normalizedName, "장해");
        addIfContained(concepts, normalizedName, "진단");

        if (normalizedName.contains("연간1회")) {
            concepts.add("연간1회");
        }

        // 보장명에서 검색 개념을 찾지 못한 경우에만 치료유형 사용
        if (concepts.isEmpty()) {
            addTreatmentConcepts(
                    concepts,
                    request
            );
        }

        return List.copyOf(concepts);
    }

    private List<String> buildConditionConcepts(
            String reason
    ) {
        String normalizedReason =
                normalize(reason);

        if (normalizedReason.contains("1년이내")) {
            return List.of("1년이내");
        }

        if (normalizedReason.contains("1년초과")) {
            return List.of("1년초과");
        }

        return List.of();
    }

    private void addIfContained(
            Set<String> concepts,
            String normalizedValue,
            String concept
    ) {
        if (normalizedValue.contains(
                normalize(concept)
        )) {
            concepts.add(concept);
        }
    }

    private void addTreatmentConcepts(
            Set<String> concepts,
            ChatMessageRequest request
    ) {
        if (request.getTreatmentTypes() == null) {
            return;
        }

        for (TreatmentType treatmentType
                : request.getTreatmentTypes()) {

            switch (treatmentType) {
                case CAST ->
                        concepts.add("깁스");

                case SURGERY ->
                        concepts.add("수술");

                case HOSPITALIZATION ->
                        concepts.add("입원");

                case DENTAL ->
                        concepts.add("치아");

                case DIAGNOSIS_ONLY ->
                        concepts.add("진단");

                case OUTPATIENT ->
                        concepts.add("통원");

                case DISABILITY ->
                        concepts.add("장해");
            }
        }
    }

    private List<String> buildClaimKeywords(
            ChatMessageRequest request
    ) {
        Set<String> keywords =
                new LinkedHashSet<>();

        if (request.getTreatmentTypes() != null) {
            for (TreatmentType treatmentType
                    : request.getTreatmentTypes()) {

                addClaimTreatmentKeywords(
                        keywords,
                        treatmentType
                );
            }
        }

        // 짧은 진단명이나 수술명도 검색에 활용
        if (request.getMessage() != null
                && !request.getMessage().isBlank()
                && request.getMessage().trim().length() <= 20) {

            keywords.add(
                    request.getMessage().trim()
            );
        }

        return List.copyOf(keywords);
    }

    private void addClaimTreatmentKeywords(
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

    private String normalize(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll("\\s+", "")
                .replace("(", "")
                .replace(")", "")
                .replace("·", "")
                .replace("-", "");
    }
}