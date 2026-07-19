package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
import com.codit.be_boda.chat.service.AnswerSource;
import com.codit.be_boda.chat.type.DentalTreatmentType;
import com.codit.be_boda.chat.type.IncidentType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DocumentAnswerGenerator {

    // 먼저 넓게 검색한 뒤 칩3 내부에서 정확한 근거만 선택
    private static final int SEARCH_LIMIT = 30;

    // 프론트에 제공할 칩3 근거 최대 개수
    private static final int EVIDENCE_LIMIT = 2;

    private final TermsChunkQueryRepository termsChunkQueryRepository;

    // 기존 문자열 응답 호출부와의 호환을 위한 메서드
    public String generateAnswer(
            Long termsDocumentId,
            ChatMessageRequest request
    ) {
        return generateStructuredAnswer(
                termsDocumentId,
                request
        ).messageContent();
    }

    // 필요서류 문자열 응답과 DTO 카드 데이터를 함께 생성
    public DocumentAnswerResult generateStructuredAnswer(
            Long termsDocumentId,
            ChatMessageRequest request
    ) {
        List<String> requiredDocuments =
                buildRequiredDocuments(request);

        String messageContent =
                buildDocumentAnswer(
                        requiredDocuments
                );

        DocumentGuideResponse documentGuide =
                buildDocumentGuide(
                        requiredDocuments
                );

        // 약관이 없는 경우 필요서류만 반환
        if (termsDocumentId == null) {
            return new DocumentAnswerResult(
                    messageContent,
                    documentGuide,
                    false,
                    List.of()
            );
        }

        List<String> keywords =
                buildSearchKeywords(request);

        List<TermsChunkInfo> searchedChunks =
                termsChunkQueryRepository
                        .findByTermsDocumentIdAndKeywords(
                                termsDocumentId,
                                keywords,
                                SEARCH_LIMIT
                        );

        // 칩3 조건에 실제로 맞는 근거만 필터링
        List<TermsChunkInfo> evidenceChunks =
                filterEvidenceChunks(
                        searchedChunks,
                        request
                );

        boolean hasSources =
                !evidenceChunks.isEmpty();

        DocumentGuideResponse documentGuideWithSources =
                bindDocumentSources(
                        documentGuide,
                        evidenceChunks
                );

        List<AnswerSource> sources =
                evidenceChunks.stream()
                        .map(chunk ->
                                new AnswerSource(
                                        chunk.chunkId(),
                                        null,
                                        null
                                )
                        )
                        .toList();

        return new DocumentAnswerResult(
                messageContent,
                documentGuideWithSources,
                hasSources,
                sources
        );
    }

    // 칩3에 관련 있는 근거만 선택
    private List<TermsChunkInfo> filterEvidenceChunks(
            List<TermsChunkInfo> chunks,
            ChatMessageRequest request
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<TermsChunkInfo> sortedChunks =
                chunks.stream()
                        .filter(chunk ->
                                isRelevantDocumentChunk(
                                        chunk,
                                        request
                                )
                        )
                        .sorted((first, second) ->
                                Integer.compare(
                                        calculateEvidenceScore(
                                                second,
                                                request
                                        ),
                                        calculateEvidenceScore(
                                                first,
                                                request
                                        )
                                )
                        )
                        .toList();

        List<TermsChunkInfo> filteredChunks =
                deduplicateByClause(
                        sortedChunks,
                        EVIDENCE_LIMIT
                );

        if (!filteredChunks.isEmpty()) {
            return filteredChunks;
        }

        // 치료별 서류를 찾지 못한 경우 공통 청구서류 조항 1개만 사용
        return chunks.stream()
                .filter(chunk ->
                        !isExcludedDocumentChunk(chunk)
                )
                .filter(this::containsCommonClaimDocuments)
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
    }

    // 하나의 조항이 여러 청크로 나뉘 경우 제거
    private List<TermsChunkInfo> deduplicateByClause(
            List<TermsChunkInfo> chunks,
            int limit
    ) {
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

        return uniqueChunks.values()
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

    private boolean isRelevantDocumentChunk(
            TermsChunkInfo chunk,
            ChatMessageRequest request
    ) {
        // 상품 전체 공통 청구서류 안내는 항상 우선
        if (isRepresentativeDocumentGuide(chunk)) {
            return true;
        }

        // 다른 유형의 특약 청구 조항 제외
        if (isExcludedDocumentChunk(chunk)) {
            return false;
        }

        if (request.getTreatmentTypes() == null
                || request.getTreatmentTypes().isEmpty()) {

            return containsCommonClaimDocuments(chunk);
        }

        return request.getTreatmentTypes()
                .stream()
                .filter(treatmentType ->
                        treatmentType != TreatmentType.OUTPATIENT
                                || !shouldMergeOutpatientDocuments(
                                request
                        )
                )
                .anyMatch(treatmentType ->
                        matchesTreatmentDocument(
                                chunk,
                                treatmentType
                        )
                );
    }

    // 근거 우선순위 계산
    private int calculateEvidenceScore(
            TermsChunkInfo chunk,
            ChatMessageRequest request
    ) {
        int score = 0;

        boolean representativeGuide =
                isRepresentativeDocumentGuide(chunk);

        if (representativeGuide) {
            // 치료별 조항을 찾지 못했을 때 사용할 공통 근거
            score += 30;
        }

        // 대표 예시 표는 모든 치료명을 포함하므로
        // 치료별 특약 조항과 동일한 가중치를 주지 않는다.
        if (!representativeGuide
                && request.getTreatmentTypes() != null) {
            for (TreatmentType treatmentType
                    : request.getTreatmentTypes()) {

                if (treatmentType == TreatmentType.OUTPATIENT
                        && shouldMergeOutpatientDocuments(
                        request
                )) {
                    continue;
                }

                if (matchesTreatmentDocument(
                        chunk,
                        treatmentType
                )) {
                    score += 100;
                }
            }
        }

        if (containsCommonClaimDocuments(chunk)) {
            score += 20;
        }

        return score;
    }

    // 상품 전체의 사고보험금 청구서류 대표 예시
    private boolean isRepresentativeDocumentGuide(
            TermsChunkInfo chunk
    ) {
        String text =
                buildNormalizedChunkText(chunk);

        return text.contains(
                "사고보험금청구서류대표예시"
        );
    }

    // 칩3에서 제외해야 하는 다른 특약·청구 유형
    private boolean isExcludedDocumentChunk(
            TermsChunkInfo chunk
    ) {
        String text =
                buildNormalizedChunkText(chunk);

        return text.contains("사망보험금을청구")
                || text.contains("지정대리청구")
                || text.contains("지정대리청구인")
                || text.contains("독감항바이러스")
                || text.contains("선지급치료비")
                || text.contains("가족관계등록부")
                || text.contains("가족관계증명서")
                || text.contains("보험금지급절차")
                || text.contains("서류접수1차심사");
    }

    // 청구서와 신분증이 함께 있는 공통 청구 조항
    private boolean containsCommonClaimDocuments(
            TermsChunkInfo chunk
    ) {
        String text =
                buildNormalizedChunkText(chunk);

        return text.contains("청구서")
                && text.contains("신분증");
    }

    // 치료 유형별 서류가 본문에 직접 포함되는지 확인
    private boolean matchesTreatmentDocument(
            TermsChunkInfo chunk,
            TreatmentType treatmentType
    ) {
        String text =
                buildNormalizedChunkText(chunk);

        return switch (treatmentType) {
            case DIAGNOSIS_ONLY ->
                    text.contains("진단서")
                            || text.contains("진단사실확인서류")
                            || text.contains("검사결과지");

            case SURGERY ->
                    text.contains("수술확인서")
                            || text.contains("수술증명서");

            case HOSPITALIZATION ->
                    text.contains("입퇴원확인서")
                            || text.contains("입원치료확인서");

            case OUTPATIENT ->
                    text.contains("통원확인서");

            case CAST ->
                    text.contains("깁스")
                            && (
                            text.contains("증명서")
                                    || text.contains("진료기록부")
                    );

            case DENTAL ->
                    text.contains("치과치료확인서")
                            || text.contains("치과진료기록")
                            || text.contains("xray")
                            || text.contains("구강내사진");

            case DISABILITY ->
                    text.contains("장해진단서")
                            || text.contains("후유장해진단서");
        };
    }

    private String buildNormalizedChunkText(
            TermsChunkInfo chunk
    ) {
        if (chunk == null) {
            return "";
        }

        return normalize(
                safe(chunk.clauseNo())
                        + safe(chunk.clauseTitle())
                        + safe(chunk.sectionTitle())
                        + safe(chunk.chunkText())
        );
    }

    private String safe(
            String value
    ) {
        return value == null ? "" : value;
    }

    private String normalize(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase()
                .replaceAll("\\s+", "")
                .replace("·", "")
                .replace("(", "")
                .replace(")", "")
                .replace("-", "");
    }

    // 사용자 입력 조건을 기준으로 필요한 서류 후보 목록 생성
    private List<String> buildRequiredDocuments(
            ChatMessageRequest request
    ) {
        Set<String> documents =
                new LinkedHashSet<>();

        documents.add("청구서(회사양식)");
        documents.add("신분증");

        if (request.getIncidentType()
                == IncidentType.INJURY) {

            documents.add("재해 입증서류");
        }

        if (request.getTreatmentTypes() == null
                || request.getTreatmentTypes().isEmpty()) {

            return new ArrayList<>(documents);
        }

        for (TreatmentType treatmentType
                : request.getTreatmentTypes()) {

            // 수술 또는 치과치료 서류로 통원 사실을 함께 확인할 수 있는 경우
            // 별도의 통원 확인서를 중복 안내하지 않는다.
            if (treatmentType == TreatmentType.OUTPATIENT
                    && shouldMergeOutpatientDocuments(
                    request
            )) {
                continue;
            }

            addTreatmentDocuments(
                    documents,
                    treatmentType,
                    request
            );
        }

        return new ArrayList<>(documents);
    }

    private boolean shouldMergeOutpatientDocuments(
            ChatMessageRequest request
    ) {
        return request.getTreatmentTypes() != null
                && (
                request.getTreatmentTypes().contains(
                        TreatmentType.SURGERY
                )
                        || request.getTreatmentTypes().contains(
                        TreatmentType.DENTAL
                )
        );
    }

    // 치료 유형별 필요서류 추가
    private void addTreatmentDocuments(
            Set<String> documents,
            TreatmentType treatmentType,
            ChatMessageRequest request
    ) {
        switch (treatmentType) {
            case DIAGNOSIS_ONLY -> {
                documents.add("진단서");
                documents.add(
                        "진단사실 확인서류 또는 검사결과지"
                );
            }

            case SURGERY ->
                    documents.add("수술 확인서");

            case HOSPITALIZATION ->
                    documents.add("입·퇴원 확인서");

            case OUTPATIENT ->
                    documents.add("통원 확인서");

            case CAST -> {
                documents.add(
                        "깁스 치료 증명서"
                );
                documents.add("진료기록부");
            }

            case DENTAL -> {
                documents.add("치과치료 확인서");
                documents.add("치과진료기록");

                if (isDentalExtraction(request)) {
                    documents.add(
                            "영구치 발치 전후 X-ray 사진"
                    );
                } else {
                    documents.add(
                            "치과치료 전후 X-ray 사진"
                    );
                }

                documents.add(
                        "구강 내 사진 또는 이에 준하는 판독 자료"
                );
            }

            case DISABILITY ->
                    documents.add("장해진단서");
        }
    }

    private boolean isDentalExtraction(
            ChatMessageRequest request
    ) {
        return request.getDentalInfo() != null
                && request.getDentalInfo()
                .getDentalTreatmentTypes() != null
                && request.getDentalInfo()
                .getDentalTreatmentTypes()
                .contains(
                        DentalTreatmentType.EXTRACTION
                );
    }

    // 약관 chunk 검색 키워드 생성
    private List<String> buildSearchKeywords(
            ChatMessageRequest request
    ) {
        Set<String> keywords =
                new LinkedHashSet<>();

        keywords.add("보험금의 청구");
        keywords.add("청구서류");
        keywords.add("사고보험금 청구서류");
        keywords.add("사고증명서");
        keywords.add("청구서");
        keywords.add("신분증");

        if (request.getIncidentType()
                == IncidentType.INJURY) {

            keywords.add("재해 입증서류");
        }

        if (request.getTreatmentTypes() == null
                || request.getTreatmentTypes().isEmpty()) {

            return new ArrayList<>(keywords);
        }

        for (TreatmentType treatmentType
                : request.getTreatmentTypes()) {

            if (treatmentType == TreatmentType.OUTPATIENT
                    && shouldMergeOutpatientDocuments(
                    request
            )) {
                continue;
            }

            addTreatmentKeywords(
                    keywords,
                    treatmentType
            );
        }

        return new ArrayList<>(keywords);
    }

    // 치료 유형별 약관 검색 키워드 추가
    private void addTreatmentKeywords(
            Set<String> keywords,
            TreatmentType treatmentType
    ) {
        switch (treatmentType) {
            case DIAGNOSIS_ONLY -> {
                keywords.add("진단서");
                keywords.add("진단사실 확인서류");
                keywords.add("검사결과지");
            }

            case SURGERY -> {
                keywords.add("수술 확인서");
                keywords.add("수술확인서");
                keywords.add("수술증명서");
            }

            case HOSPITALIZATION -> {
                keywords.add("입·퇴원 확인서");
                keywords.add("입퇴원 확인서");
                keywords.add("입원치료확인서");
            }

            case OUTPATIENT -> {
                keywords.add("통원 확인서");
                keywords.add("통원확인서");
            }

            case CAST -> {
                keywords.add("깁스");
                keywords.add("Cast");
                keywords.add(
                        "깁스 치료 증명서"
                );
                keywords.add("진료기록부");
            }

            case DENTAL -> {
                keywords.add("치과치료 확인서");
                keywords.add("치과진료기록");
                keywords.add("X-ray");
                keywords.add("구강 내 사진");
            }

            case DISABILITY -> {
                keywords.add("장해진단서");
                keywords.add("후유장해진단서");
            }
        }
    }

    // 사용자에게 보여줄 필요서류 답변 생성
    private String buildDocumentAnswer(
            List<String> requiredDocuments
    ) {
        List<String> mandatoryDocuments =
                requiredDocuments.stream()
                        .filter(this::isRequiredDocument)
                        .toList();

        List<String> optionalDocuments =
                requiredDocuments.stream()
                        .filter(document ->
                                !isRequiredDocument(document)
                        )
                        .toList();

        StringBuilder builder = new StringBuilder(
                "필요서류 후보를 확인했어요.\n\n"
        );

        builder.append("[필수 서류]\n")
                .append(
                        buildDocumentLines(
                                mandatoryDocuments
                        )
                );

        if (!optionalDocuments.isEmpty()) {
            builder.append("\n[추가 요청 가능 서류]\n")
                    .append(
                            buildDocumentLines(
                                    optionalDocuments
                            )
                    );
        }

        return builder.toString();
    }

    private String buildDocumentLines(
            List<String> requiredDocuments
    ) {
        StringBuilder builder =
                new StringBuilder();

        for (String document
                : requiredDocuments) {

            builder.append("- ")
                    .append(document)
                    .append("\n");
        }

        return builder.toString();
    }

    // 프론트 카드 UI에서 사용할 documentGuide 생성
    private DocumentGuideResponse buildDocumentGuide(
            List<String> requiredDocuments
    ) {
        return DocumentGuideResponse.builder()
                .documents(
                        buildDocumentItems(
                                requiredDocuments
                        )
                )
                .hasSources(false)
                .sourceChunkIds(List.of())
                .build();
    }

    private List<DocumentGuideResponse.DocumentItem>
    buildDocumentItems(
            List<String> requiredDocuments
    ) {
        return requiredDocuments.stream()
                .map(document ->
                        DocumentGuideResponse
                                .DocumentItem
                                .builder()
                                .name(document)
                                .description(
                                        buildDocumentDescription(
                                                document
                                        )
                                )
                                .required(
                                        isRequiredDocument(
                                                document
                                        )
                                )
                                .hasSources(false)
                                .sourceChunkIds(List.of())
                                .build()
                )
                .toList();
    }

    private boolean isRequiredDocument(
            String document
    ) {
        return !"구강 내 사진 또는 이에 준하는 판독 자료".equals(
                document
        );
    }

    // 각 서류 카드에 해당 서류를 직접 뒷받침하는 약관 chunkId 연결
    private DocumentGuideResponse bindDocumentSources(
            DocumentGuideResponse documentGuide,
            List<TermsChunkInfo> evidenceChunks
    ) {
        if (documentGuide == null) {
            return null;
        }

        List<TermsChunkInfo> safeEvidenceChunks =
                evidenceChunks == null
                        ? List.of()
                        : evidenceChunks;

        List<DocumentGuideResponse.DocumentItem> documentsWithSources =
                documentGuide.getDocuments() == null
                        ? List.of()
                        : documentGuide.getDocuments()
                          .stream()
                          .map(document -> {
                              List<Long> sourceChunkIds =
                                      safeEvidenceChunks.stream()
                                              .filter(chunk ->
                                                      matchesDocument(
                                                              chunk,
                                                              document.getName()
                                                      )
                                              )
                                              .map(TermsChunkInfo::chunkId)
                                              .filter(chunkId ->
                                                      chunkId != null
                                              )
                                              .distinct()
                                              .toList();

                              return DocumentGuideResponse.DocumentItem
                                     .builder()
                                     .name(document.getName())
                                     .description(
                                             document.getDescription()
                                     )
                                     .required(
                                             document.getRequired()
                                     )
                                     .hasSources(
                                             !sourceChunkIds.isEmpty()
                                     )
                                     .sourceChunkIds(
                                             sourceChunkIds
                                     )
                                     .build();
                          })
                          .toList();

        List<Long> allSourceChunkIds =
                safeEvidenceChunks.stream()
                        .map(TermsChunkInfo::chunkId)
                        .filter(chunkId ->
                                chunkId != null
                        )
                        .distinct()
                        .toList();

        return DocumentGuideResponse.builder()
                .documents(documentsWithSources)
                .hasSources(!allSourceChunkIds.isEmpty())
                .sourceChunkIds(allSourceChunkIds)
                .build();
    }

    private boolean matchesDocument(
            TermsChunkInfo chunk,
            String document
    ) {
        String text = buildNormalizedChunkText(chunk);

        return switch (document) {
            case "청구서(회사양식)" ->
                    text.contains("청구서");

            case "신분증" ->
                    text.contains("신분증");

            case "재해 입증서류" ->
                    text.contains("재해입증서류")
                            || text.contains("사고사실확인서류");

            case "진단서" ->
                    text.contains("진단서");

            case "진단사실 확인서류 또는 검사결과지" ->
                    text.contains("진단사실확인서류")
                            || text.contains("검사결과지");

            case "수술 확인서" ->
                    text.contains("수술확인서")
                            || text.contains("수술증명서");

            case "입·퇴원 확인서" ->
                    text.contains("입퇴원확인서")
                            || text.contains("입원치료확인서");

            case "통원 확인서" ->
                    text.contains("통원확인서");

            case "깁스 치료 증명서" ->
                    text.contains("깁스")
                            && text.contains("증명서");

            case "진료기록부" ->
                    text.contains("진료기록부");

            case "치과치료 확인서" ->
                    text.contains("치과치료확인서");

            case "치과진료기록" ->
                    text.contains("치과진료기록");

            case "영구치 발치 전후 X-ray 사진",
                 "치과치료 전후 X-ray 사진" ->
                    text.contains("xray");

            case "구강 내 사진 또는 이에 준하는 판독 자료" ->
                    text.contains("구강내사진")
                            || text.contains("판독자료");

            case "장해진단서" ->
                    text.contains("장해진단서")
                            || text.contains("후유장해진단서");

            default -> false;
        };
    }

    private String buildDocumentDescription(
            String document
    ) {
        return switch (document) {
            case "청구서(회사양식)" ->
                    "보험사 양식에 맞춰 작성하는 보험금 청구서예요.";

            case "신분증" ->
                    "보험금 청구자 본인 확인을 위한 서류예요.";

            case "재해 입증서류" ->
                    "상해 또는 재해 사실을 확인하기 위한 서류예요.";

            case "진단서" ->
                    "진단명과 진단 사실을 확인하기 위한 서류예요.";

            case "진단사실 확인서류 또는 검사결과지" ->
                    "진단 근거가 되는 검사 결과를 확인하기 위한 서류예요.";

            case "수술 확인서" ->
                    "수술명과 수술일자를 확인하기 위한 서류예요.";

            case "입·퇴원 확인서" ->
                    "입원 기간과 입·퇴원 사실을 확인하기 위한 서류예요.";

            case "통원 확인서" ->
                    "통원 치료 사실을 확인하기 위한 서류예요.";

            case "깁스 치료 증명서" ->
                    "깁스 치료 여부를 확인하기 위한 서류예요.";

            case "진료기록부" ->
                    "치료 내용과 경과를 확인하기 위한 진료 기록이에요.";

            case "치과치료 확인서" ->
                    "치과 치료 종류와 치료 내용을 확인하기 위한 서류예요.";

            case "치과진료기록" ->
                    "치과 치료 내역과 치료 경과를 확인하기 위한 필수 서류예요.";

            case "영구치 발치 전후 X-ray 사진" ->
                    "영구치 발치 전후의 치아 상태를 확인하기 위한 필수 자료예요.";

            case "치과치료 전후 X-ray 사진" ->
                    "치과 치료 전후의 치아 상태를 확인하기 위한 필수 자료예요.";

            case "구강 내 사진 또는 이에 준하는 판독 자료" ->
                    "보험사가 심사에 필요하다고 판단하는 경우 추가로 요청할 수 있어요.";

            case "장해진단서" ->
                    "장해 상태와 정도를 확인하기 위한 서류예요.";

            default ->
                    "보험금 청구 심사에 필요할 수 있는 서류예요.";
        };
    }
}
