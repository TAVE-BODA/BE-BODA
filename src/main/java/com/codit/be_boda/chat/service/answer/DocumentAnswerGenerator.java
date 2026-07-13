package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
import com.codit.be_boda.chat.service.AnswerSource;
import com.codit.be_boda.chat.type.IncidentType;
import com.codit.be_boda.chat.type.TreatmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DocumentAnswerGenerator {

    private static final int EVIDENCE_LIMIT = 10;

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
                buildDocumentAnswer(requiredDocuments);

        DocumentGuideResponse documentGuide =
                buildDocumentGuide(requiredDocuments);

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

        List<TermsChunkInfo> evidenceChunks =
                termsChunkQueryRepository
                        .findByTermsDocumentIdAndKeywords(
                                termsDocumentId,
                                keywords,
                                EVIDENCE_LIMIT
                        );

        boolean hasSources =
                !evidenceChunks.isEmpty();

        // GET sources API에서 사용할 근거 저장 정보
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
                documentGuide,
                hasSources,
                sources
        );
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
            addTreatmentDocuments(
                    documents,
                    treatmentType
            );
        }

        return new ArrayList<>(documents);
    }

    // 치료 유형별 필요서류 추가
    private void addTreatmentDocuments(
            Set<String> documents,
            TreatmentType treatmentType
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
                        "깁스(Cast) 치료 증명서"
                );
                documents.add("진료기록부");
            }

            case DENTAL -> {
                documents.add("치과치료 확인서");
                documents.add("치과진료기록");
                documents.add(
                        "X-ray 사진 또는 구강 내 사진"
                );
            }

            case DISABILITY ->
                    documents.add("장해진단서");
        }
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
        keywords.add("추가서류");

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
                        "깁스(Cast)치료 증명서"
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
        return "필요서류 후보를 확인했어요.\n\n"
                + "[필요서류 후보]\n"
                + buildDocumentLines(
                requiredDocuments
        );
    }

    // 필요서류 목록을 문자열로 변환
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
                .build();
    }

    // 필요서류 목록을 DocumentItem 배열로 변환
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
                                .required(true)
                                .build()
                )
                .toList();
    }

    // 필요서류별 설명 생성
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

            case "깁스(Cast) 치료 증명서" ->
                    "깁스 치료 여부를 확인하기 위한 서류예요.";

            case "진료기록부" ->
                    "치료 내용과 경과를 확인하기 위한 진료 기록이에요.";

            case "치과치료 확인서" ->
                    "치과 치료 종류와 치료 내용을 확인하기 위한 서류예요.";

            case "치과진료기록" ->
                    "치과 진료 내역을 확인하기 위한 기록이에요.";

            case "X-ray 사진 또는 구강 내 사진" ->
                    "치아 치료 상태를 확인하기 위한 이미지 자료예요.";

            case "장해진단서" ->
                    "장해 상태와 정도를 확인하기 위한 서류예요.";

            default ->
                    "보험금 청구 심사에 필요할 수 있는 서류예요.";
        };
    }
}