package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
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

    public String generateAnswer(Long termsDocumentId, ChatMessageRequest request) {
        if (termsDocumentId == null) {
            return """
                    필요서류 안내를 위해서는 약관 문서 정보가 필요해요.
                    
                    현재 채팅 세션에 연결된 약관 문서 ID가 없어 약관 근거를 조회할 수 없습니다.
                    """;
        }

        List<String> requiredDocuments = buildRequiredDocuments(request);
        List<String> keywords = buildSearchKeywords(request);

        List<TermsChunkInfo> evidenceChunks =
                termsChunkQueryRepository.findByTermsDocumentIdAndKeywords(
                        termsDocumentId,
                        keywords,
                        EVIDENCE_LIMIT
                );

        if (evidenceChunks.isEmpty()) {
            return buildNoEvidenceAnswer(requiredDocuments);
        }

        return buildEvidenceBasedAnswer(requiredDocuments, evidenceChunks);
    }

    private List<String> buildRequiredDocuments(ChatMessageRequest request) {
        Set<String> documents = new LinkedHashSet<>();

        documents.add("청구서(회사양식)");
        documents.add("신분증");

        if (request.getIncidentType() == IncidentType.INJURY) {
            documents.add("재해 입증서류");
        }

        if (request.getTreatmentTypes() == null || request.getTreatmentTypes().isEmpty()) {
            return new ArrayList<>(documents);
        }

        for (TreatmentType treatmentType : request.getTreatmentTypes()) {
            addTreatmentDocuments(documents, treatmentType);
        }

        return new ArrayList<>(documents);
    }

    private void addTreatmentDocuments(Set<String> documents, TreatmentType treatmentType) {
        switch (treatmentType) {
            case DIAGNOSIS_ONLY -> {
                documents.add("진단서");
                documents.add("진단사실 확인서류 또는 검사결과지");
            }
            case SURGERY -> documents.add("수술 확인서");
            case HOSPITALIZATION -> documents.add("입·퇴원 확인서");
            case OUTPATIENT -> documents.add("통원 확인서");
            case CAST -> {
                documents.add("깁스(Cast) 치료 증명서");
                documents.add("진료기록부");
            }
            case DENTAL -> {
                documents.add("치과치료 확인서");
                documents.add("치과진료기록");
                documents.add("X-ray 사진 또는 구강 내 사진");
            }
            case DISABILITY -> documents.add("장해진단서");
        }
    }

    private List<String> buildSearchKeywords(ChatMessageRequest request) {
        Set<String> keywords = new LinkedHashSet<>();

        keywords.add("보험금의 청구");
        keywords.add("청구서류");
        keywords.add("사고보험금 청구서류");
        keywords.add("사고증명서");
        keywords.add("청구서");
        keywords.add("신분증");
        keywords.add("추가서류");

        if (request.getIncidentType() == IncidentType.INJURY) {
            keywords.add("재해 입증서류");
        }

        if (request.getTreatmentTypes() == null || request.getTreatmentTypes().isEmpty()) {
            return new ArrayList<>(keywords);
        }

        for (TreatmentType treatmentType : request.getTreatmentTypes()) {
            addTreatmentKeywords(keywords, treatmentType);
        }

        return new ArrayList<>(keywords);
    }

    private void addTreatmentKeywords(Set<String> keywords, TreatmentType treatmentType) {
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
                keywords.add("깁스(Cast)치료 증명서");
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

    private String buildEvidenceBasedAnswer(
            List<String> requiredDocuments,
            List<TermsChunkInfo> evidenceChunks
    ) {
        StringBuilder answer = new StringBuilder();

        answer.append("약관에서 확인된 보험금 청구서류 근거를 기준으로 안내드릴게요.\n\n")
                .append("[필요서류 후보]\n")
                .append(buildDocumentLines(requiredDocuments))
                .append("\n")
                .append("[약관 근거]\n")
                .append(buildEvidenceLines(evidenceChunks))
                .append("\n")
                .append("※ 위 서류는 약관에서 확인되는 대표적인 청구서류 후보예요.\n")
                .append("※ 실제 청구 시에는 보험사 심사 과정에서 추가서류를 요청할 수 있어요.");

        return answer.toString();
    }

    private String buildNoEvidenceAnswer(List<String> requiredDocuments) {
        StringBuilder answer = new StringBuilder();

        answer.append("현재 연결된 약관 chunk에서 필요서류 근거를 찾지 못했어요.\n\n")
                .append("다만 입력하신 치료 유형 기준으로 일반적으로 준비할 수 있는 서류 후보는 아래와 같아요.\n\n")
                .append("[필요서류 후보]\n")
                .append(buildDocumentLines(requiredDocuments))
                .append("\n")
                .append("정확한 필요서류는 약관의 보험금 청구 조항 또는 보험사 청구서류 안내 확인이 필요해요.");

        return answer.toString();
    }

    private String buildDocumentLines(List<String> requiredDocuments) {
        StringBuilder builder = new StringBuilder();

        for (String document : requiredDocuments) {
            builder.append("- ")
                    .append(document)
                    .append("\n");
        }

        return builder.toString();
    }

    private String buildEvidenceLines(List<TermsChunkInfo> evidenceChunks) {
        StringBuilder builder = new StringBuilder();

        for (TermsChunkInfo chunk : evidenceChunks) {
            if (isIrrelevantEvidence(chunk)) {
                continue;
            }

            appendEvidenceLine(builder, chunk);
        }

        if (!builder.isEmpty()) {
            return builder.toString();
        }

        for (TermsChunkInfo chunk : evidenceChunks) {
            appendEvidenceLine(builder, chunk);
        }

        return builder.toString();
    }

    private void appendEvidenceLine(StringBuilder builder, TermsChunkInfo chunk) {
        builder.append("- ");

        String title = getEvidenceTitle(chunk);

        if (!title.isBlank()) {
            builder.append(title).append(" ");
        }

        builder.append("(chunk_id: ")
                .append(chunk.chunkId())
                .append(")\n")
                .append("  ")
                .append(buildSnippet(chunk.chunkText()))
                .append("\n");
    }

    private String getEvidenceTitle(TermsChunkInfo chunk) {
        if (chunk.clauseNo() != null && chunk.clauseTitle() != null) {
            return chunk.clauseNo() + " " + chunk.clauseTitle();
        }

        if (chunk.clauseTitle() != null && !chunk.clauseTitle().isBlank()) {
            return chunk.clauseTitle();
        }

        if (chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank()) {
            return chunk.sectionTitle();
        }

        return "";
    }

    private boolean isIrrelevantEvidence(TermsChunkInfo chunk) {
        String title = getEvidenceTitle(chunk);
        String chunkText = chunk.chunkText();

        String target = (title + " " + chunkText);

        return target.contains("지정대리청구인")
                || target.contains("이 특약의 사망보험금")
                || target.contains("사망보험금을 청구");
    }

    private String buildSnippet(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return "근거 본문 없음";
        }

        return chunkText.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}