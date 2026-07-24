package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.AmountGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
import com.codit.be_boda.chat.service.AnswerSource;
import com.codit.be_boda.chat.type.DentalTreatmentType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ClaimEvidenceFinder {

    // 중복 조항 제거를 위해 먼저 넓게 검색
    private static final int CLAIM_SEARCH_LIMIT = 30;

    // 칩1 전체 근거 개수
    private static final int SOURCE_LIMIT = 2;

    // 사랑니·매복치 제외 판단은 가장 직접적인 근거 한 개만 노출
    private static final int DENTAL_EXCLUSION_SOURCE_LIMIT = 1;

    // 칩2 카드 한 개당 근거 개수
    private static final int SOURCE_LIMIT_PER_ITEM = 1;

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

        List<TermsChunkInfo> searchedChunks =
                termsChunkQueryRepository
                        .findClaimByTermsDocumentIdAndKeywords(
                                termsDocumentId,
                                keywords,
                                CLAIM_SEARCH_LIMIT
                        );

        return deduplicateByClause(
                searchedChunks.stream()
                        .filter(chunk ->
                                isRelevantClaimChunk(
                                        chunk,
                                        request
                                )
                        )
                        .toList(),
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
     * 사랑니·제3대구치·매복치 발치에 해당하는 약관 제외 근거 검색
     */
    public List<AnswerSource> findDentalExclusionSources(
            Long termsDocumentId,
            ChatMessageRequest request
    ) {
        if (termsDocumentId == null
                || !isWisdomToothOrImpactedRequest(
                request
        )) {
            return List.of();
        }

        List<String> keywords = List.of(
                "사랑니",
                "제3대구치",
                "매복치",
                "매몰치",
                "맹출장애"
        );

        List<TermsChunkInfo> searchedChunks =
                termsChunkQueryRepository
                        .findClaimByTermsDocumentIdAndKeywords(
                                termsDocumentId,
                                keywords,
                                CLAIM_SEARCH_LIMIT
                        );

        return deduplicateByClause(
                searchedChunks.stream()
                        .filter(
                                this::isDentalExclusionChunk
                        )
                        .toList(),
                DENTAL_EXCLUSION_SOURCE_LIMIT
        )
                .stream()
                .map(chunk -> new AnswerSource(
                        chunk.chunkId(),
                        null,
                        null
                ))
                .toList();
    }

    private boolean isWisdomToothOrImpactedRequest(
            ChatMessageRequest request
    ) {
        if (request == null
                || request.getMessage() == null
                || request.getTreatmentTypes() == null
                || !request.getTreatmentTypes().contains(
                TreatmentType.DENTAL
        )
                || request.getDentalInfo() == null
                || request.getDentalInfo()
                .getDentalTreatmentTypes() == null
                || !request.getDentalInfo()
                .getDentalTreatmentTypes()
                .contains(
                        DentalTreatmentType.EXTRACTION
                )) {
            return false;
        }

        String message = normalize(
                request.getMessage()
        );

        return message.contains("사랑니")
                || message.contains("제3대구치")
                || message.contains("매복치")
                || message.contains("매몰치")
                || message.contains("부분매복")
                || message.contains("완전매복");
    }

    private boolean isDentalExclusionChunk(
            TermsChunkInfo chunk
    ) {
        String text = normalize(
                safe(chunk.clauseNo())
                        + safe(chunk.clauseTitle())
                        + safe(chunk.sectionTitle())
                        + safe(chunk.chunkText())
        );

        boolean containsExcludedDentalCondition =
                text.contains("사랑니")
                        || text.contains("제3대구치")
                        || text.contains("매복치")
                        || text.contains("매몰치")
                        || text.contains("맹출장애");

        boolean containsExclusionMeaning =
                text.contains("제외")
                        || text.contains("보장의대상이되지않")
                        || text.contains("지급하지않");

        return containsExcludedDentalCondition
                && containsExclusionMeaning;
    }

    // 하나의 조항이 여러 청크로 나뉘 경우 제거
    private List<TermsChunkInfo> deduplicateByClause(
            List<TermsChunkInfo> chunks,
            int limit
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Map<String, TermsChunkInfo> uniqueChunks =
                new LinkedHashMap<>();

        for (TermsChunkInfo chunk : chunks) {
            if (chunk == null || chunk.chunkId() == null) {
                continue;
            }

            uniqueChunks.putIfAbsent(
                    buildClauseKey(chunk),
                    chunk
            );
        }

        return new ArrayList<>(
                uniqueChunks.values()
        )
                .stream()
                .limit(limit)
                .toList();
    }

    private String buildClauseKey(
            TermsChunkInfo chunk
    ) {
        if (chunk.clauseId() != null) {
            return "CLAUSE_ID:"
                    + chunk.clauseId();
        }

        String clauseKey = normalize(
                safe(chunk.clauseNo())
                        + safe(chunk.clauseTitle())
        );

        if (!clauseKey.isBlank()) {
            return "CLAUSE:" + clauseKey;
        }

        String sectionKey =
                normalize(chunk.sectionTitle());

        if (!sectionKey.isBlank()) {
            return "SECTION:" + sectionKey;
        }

        return "CHUNK_ID:" + chunk.chunkId();
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
        addIfContained(concepts, normalizedName, "상급병실");
        addIfContained(concepts, normalizedName, "1인실");
        addIfContained(concepts, normalizedName, "종합병원이상");
        addIfContained(concepts, normalizedName, "종합병원");
        addIfContained(concepts, normalizedName, "상급종합병원");
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

            if (request.getTreatmentTypes().contains(
                    TreatmentType.DENTAL
            )) {
                addDentalClaimKeywords(
                        keywords,
                        request
                );
            }

            if (request.getTreatmentTypes().contains(
                    TreatmentType.DIAGNOSIS_ONLY
            )) {
                addDiagnosisClaimKeywords(
                        keywords,
                        request.getMessage()
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

    private void addDentalClaimKeywords(
            Set<String> keywords,
            ChatMessageRequest request
    ) {
        if (request.getDentalInfo() == null
                || request.getDentalInfo()
                .getDentalTreatmentTypes() == null) {
            return;
        }

        String message = normalize(
                request.getMessage()
        );

        for (DentalTreatmentType treatmentType
                : request.getDentalInfo()
                .getDentalTreatmentTypes()) {

            switch (treatmentType) {
                case EXTRACTION -> {
                    keywords.add("영구치 발치");
                    keywords.add("발치보험금");
                    keywords.add("치아발거");
                }

                case ROOT_CANAL -> {
                    keywords.add("신경치료");
                    keywords.add("치수치료");
                    keywords.add("근관치료");
                }

                case CROWN_IMPLANT -> {
                    Set<String> requestedConcepts =
                            buildRequestedProstheticConcepts(
                                    message
                            );

                    if (requestedConcepts.isEmpty()) {
                        keywords.add("크라운");
                        keywords.add("임플란트");
                        keywords.add("브릿지");
                        keywords.add("틀니");
                    } else {
                        keywords.addAll(
                                requestedConcepts
                        );
                    }
                }

                case FILLING -> {
                    Set<String> requestedConcepts =
                            buildRequestedFillingConcepts(
                                    message
                            );

                    if (requestedConcepts.isEmpty()) {
                        keywords.add("충전치료");
                        keywords.add("레진");
                        keywords.add("인레이");
                        keywords.add("아말감");
                    } else {
                        keywords.addAll(
                                requestedConcepts
                        );
                    }
                }
            }
        }
    }

    private void addDiagnosisClaimKeywords(
            Set<String> keywords,
            String message
    ) {
        String normalizedMessage =
                normalize(message);

        List<String> concepts =
                List.of(
                        "화상",
                        "부식",
                        "디스크",
                        "추간판",
                        "뇌혈관",
                        "뇌졸중",
                        "뇌출혈",
                        "뇌경색",
                        "허혈성",
                        "심장",
                        "협심증",
                        "심근경색",
                        "크론",
                        "궤양성대장염",
                        "대장염",
                        "원형탈모",
                        "탈모",
                        "용종",
                        "폴립",
                        "선종",
                        "암"
                );

        concepts.stream()
                .filter(normalizedMessage::contains)
                .forEach(keywords::add);
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

    private boolean isRelevantClaimChunk(
            TermsChunkInfo chunk,
            ChatMessageRequest request
    ) {
        if (chunk == null
                || request.getTreatmentTypes() == null
                || request.getTreatmentTypes().isEmpty()) {
            return false;
        }

        String text = normalize(
                safe(chunk.clauseNo())
                        + safe(chunk.clauseTitle())
                        + safe(chunk.sectionTitle())
                        + safe(chunk.chunkText())
        );

        for (TreatmentType treatmentType
                : request.getTreatmentTypes()) {

            if (isRelevantForTreatment(
                    text,
                    treatmentType,
                    request
            )) {
                return true;
            }
        }

        return false;
    }

    private boolean isRelevantForTreatment(
            String text,
            TreatmentType treatmentType,
            ChatMessageRequest request
    ) {
        return switch (treatmentType) {
            case CAST ->
                    containsAny(
                            text,
                            "깁스",
                            "cast",
                            "부목"
                    );

            case SURGERY ->
                    text.contains("수술");

            case HOSPITALIZATION ->
                    containsAny(
                            text,
                            "입원",
                            "병실"
                    );

            case DENTAL ->
                    isRelevantDentalClaimChunk(
                            text,
                            request
                    );

            case DIAGNOSIS_ONLY ->
                    isRelevantDiagnosisClaimChunk(
                            text,
                            request.getMessage()
                    );

            case OUTPATIENT ->
                    containsAny(
                            text,
                            "통원",
                            "외래",
                            "실손의료",
                            "처방조제"
                    );

            case DISABILITY ->
                    text.contains("장해");
        };
    }

    private boolean isRelevantDentalClaimChunk(
            String text,
            ChatMessageRequest request
    ) {
        if (request.getDentalInfo() == null
                || request.getDentalInfo()
                .getDentalTreatmentTypes() == null
                || request.getDentalInfo()
                .getDentalTreatmentTypes()
                .isEmpty()) {
            return containsAny(
                    text,
                    "치아",
                    "치과"
            );
        }

        String message = normalize(
                request.getMessage()
        );

        for (DentalTreatmentType treatmentType
                : request.getDentalInfo()
                .getDentalTreatmentTypes()) {

            if (matchesDentalTreatmentChunk(
                    text,
                    message,
                    treatmentType
            )) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesDentalTreatmentChunk(
            String text,
            String message,
            DentalTreatmentType treatmentType
    ) {
        return switch (treatmentType) {
            case EXTRACTION ->
                    containsAny(
                            text,
                            "영구치발치",
                            "발치보험금",
                            "치아발거"
                    );

            case ROOT_CANAL ->
                    containsAny(
                            text,
                            "신경치료",
                            "치수치료",
                            "근관치료"
                    );

            case CROWN_IMPLANT -> {
                Set<String> requestedConcepts =
                        buildRequestedProstheticConcepts(
                                message
                        );

                yield requestedConcepts.isEmpty()
                        ? containsAny(
                        text,
                        "크라운",
                        "임플란트",
                        "브릿지",
                        "고정성가공의치",
                        "틀니",
                        "가철성의치"
                )
                        : requestedConcepts.stream()
                          .anyMatch(text::contains);
            }

            case FILLING -> {
                Set<String> requestedConcepts =
                        buildRequestedFillingConcepts(
                                message
                        );

                yield requestedConcepts.isEmpty()
                        ? containsAny(
                        text,
                        "충전",
                        "레진",
                        "인레이",
                        "온레이",
                        "아말감",
                        "글래스아이노머"
                )
                        : requestedConcepts.stream()
                          .anyMatch(text::contains);
            }
        };
    }

    private Set<String> buildRequestedProstheticConcepts(
            String message
    ) {
        Set<String> concepts =
                new LinkedHashSet<>();

        if (message.contains("크라운")) {
            concepts.add("크라운");
        }

        if (message.contains("임플란트")) {
            concepts.add("임플란트");
        }

        if (message.contains("브릿지")
                || message.contains("고정성가공의치")) {
            concepts.add("브릿지");
            concepts.add("고정성가공의치");
        }

        if (message.contains("틀니")
                || message.contains("가철성의치")) {
            concepts.add("틀니");
            concepts.add("가철성의치");
        }

        return concepts;
    }

    private Set<String> buildRequestedFillingConcepts(
            String message
    ) {
        Set<String> concepts =
                new LinkedHashSet<>();

        if (message.contains("레진")
                || message.contains("복합레진")) {
            concepts.add("레진");
            concepts.add("복합레진");
        }

        if (message.contains("인레이")) {
            concepts.add("인레이");
        }

        if (message.contains("온레이")) {
            concepts.add("온레이");
        }

        if (message.contains("아말감")) {
            concepts.add("아말감");
        }

        if (message.contains("글래스아이노머")) {
            concepts.add("글래스아이노머");
        }

        return concepts;
    }

    private boolean isRelevantDiagnosisClaimChunk(
            String text,
            String message
    ) {
        String normalizedMessage =
                normalize(message);

        List<String> diagnosisConcepts =
                List.of(
                        "화상",
                        "부식",
                        "디스크",
                        "추간판",
                        "뇌혈관",
                        "뇌졸중",
                        "뇌출혈",
                        "뇌경색",
                        "허혈성",
                        "심장",
                        "협심증",
                        "심근경색",
                        "크론",
                        "궤양성대장염",
                        "대장염",
                        "원형탈모",
                        "탈모",
                        "용종",
                        "폴립",
                        "선종",
                        "암"
                );

        List<String> matchedConcepts =
                diagnosisConcepts.stream()
                        .filter(
                                normalizedMessage::contains
                        )
                        .toList();

        if (!matchedConcepts.isEmpty()) {
            return matchedConcepts.stream()
                    .anyMatch(text::contains);
        }

        return !normalizedMessage.isBlank()
                && normalizedMessage.length() <= 20
                && text.contains(normalizedMessage);
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(
                    normalize(candidate)
            )) {
                return true;
            }
        }

        return false;
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
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }
}