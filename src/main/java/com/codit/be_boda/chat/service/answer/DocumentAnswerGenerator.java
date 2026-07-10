package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.dto.response.DocumentGuideResponse;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository;
import com.codit.be_boda.chat.repository.TermsChunkQueryRepository.TermsChunkInfo;
import com.codit.be_boda.chat.type.IncidentType;
import com.codit.be_boda.chat.type.TreatmentType;
import com.codit.be_boda.chat.service.AnswerSource;

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
    public String generateAnswer(Long termsDocumentId, ChatMessageRequest request) {
        return generateStructuredAnswer(termsDocumentId, request).messageContent();
    }

    // 필요서류 문자열 응답과 DTO 카드 데이터를 함께 생성하는 메인 메서드
    public DocumentAnswerResult generateStructuredAnswer(Long termsDocumentId, ChatMessageRequest request) {
        if (termsDocumentId == null) {
            List<String> requiredDocuments = buildRequiredDocuments(request);

            String messageContent = buildNoTermsDocumentAnswer(requiredDocuments);

            DocumentGuideResponse documentGuide = buildDocumentGuide(
                    requiredDocuments,
                    List.of(),
                    false,
                    "약관이 업로드되지 않아 약관 근거 보기는 제공되지 않아요."
            );

            return new DocumentAnswerResult(messageContent, documentGuide, false, List.of());        }

        List<String> requiredDocuments = buildRequiredDocuments(request);
        List<String> keywords = buildSearchKeywords(request);

        List<TermsChunkInfo> evidenceChunks =
                termsChunkQueryRepository.findByTermsDocumentIdAndKeywords(
                        termsDocumentId,
                        keywords,
                        EVIDENCE_LIMIT
                );

        boolean hasEvidence = !evidenceChunks.isEmpty();

        // 필요서류 약관 근거 source 생성
        List<AnswerSource> sources = evidenceChunks.stream()
                .map(chunk -> new AnswerSource(
                        chunk.chunkId(),
                        null,
                        null
                ))
                .toList();

        String messageContent = hasEvidence
                ? buildEvidenceBasedAnswer(requiredDocuments)
                : buildNoEvidenceAnswer(requiredDocuments);

        String notice = hasEvidence
                ? "약관 근거 보기를 통해 자세한 내용을 확인할 수 있어요."
                : "현재 연결된 약관에서 필요서류 근거를 찾지 못했어요.";

        DocumentGuideResponse documentGuide = buildDocumentGuide(
                requiredDocuments,
                evidenceChunks,
                hasEvidence,
                notice
        );

        return new DocumentAnswerResult(
                messageContent,
                documentGuide,
                hasEvidence,
                sources
        );
    }

    // 사용자 입력 조건을 기준으로 필요한 서류 후보 목록을 생성
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

    // 치료 유형별로 추가로 필요한 서류를 documents에 추가
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

    // 약관 chunk 검색에 사용할 키워드 목록을 생성
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

    // 치료 유형별로 약관 검색에 필요한 키워드를 keywords에 추가
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

    // 약관 근거가 존재할 때 사용자에게 보여줄 문자열 답변을 생성
    private String buildEvidenceBasedAnswer(List<String> requiredDocuments) {
        return "필요서류 후보를 확인했어요.\n\n"
                + "[필요서류 후보]\n"
                + buildDocumentLines(requiredDocuments)
                + "\n약관 근거는 [근거 보기]에서 확인할 수 있어요.";
    }

    // 약관 근거를 찾지 못했을 때 사용자에게 보여줄 문자열 답변을 생성
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

    // 약관 문서가 없는 경우 사용자에게 보여줄 문자열 답변을 생성
    private String buildNoTermsDocumentAnswer(List<String> requiredDocuments) {
        return "필요서류 후보를 확인했어요.\n\n"
                + "[필요서류 후보]\n"
                + buildDocumentLines(requiredDocuments)
                + "\n약관이 업로드되지 않아 약관 근거 보기는 제공되지 않아요.";
    }

    // 필요서류 목록을 messageContent에 들어갈 bullet 문자열로 변환
    private String buildDocumentLines(List<String> requiredDocuments) {
        StringBuilder builder = new StringBuilder();

        for (String document : requiredDocuments) {
            builder.append("- ")
                    .append(document)
                    .append("\n");
        }

        return builder.toString();
    }

    // 약관 근거 chunk 목록을 messageContent에 들어갈 문자열로 변환
    private String buildEvidenceLines(List<TermsChunkInfo> evidenceChunks) {
        StringBuilder builder = new StringBuilder();

        for (TermsChunkInfo chunk : filterEvidenceChunks(evidenceChunks)) {
            appendEvidenceLine(builder, chunk);
        }

        return builder.toString();
    }

    // messageContent의 약관 근거 한 줄을 생성
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

    // 프론트 카드 UI에서 사용할 documentGuide DTO를 생성
    private DocumentGuideResponse buildDocumentGuide(
            List<String> requiredDocuments,
            List<TermsChunkInfo> evidenceChunks,
            Boolean evidenceAvailable,
            String notice
    ) {
        return DocumentGuideResponse.builder()
                .documents(buildDocumentItems(requiredDocuments))
                .evidences(buildEvidenceItems(evidenceChunks))
                .evidenceAvailable(evidenceAvailable)
                .notice(notice)
                .build();
    }

    // 필요서류 문자열 목록을 프론트에서 바로 사용할 documents 배열로 변환
    private List<DocumentGuideResponse.DocumentItem> buildDocumentItems(List<String> requiredDocuments) {
        return requiredDocuments.stream()
                .map(document -> DocumentGuideResponse.DocumentItem.builder()
                        .name(document)
                        .description(buildDocumentDescription(document))
                        .required(true)
                        .build())
                .toList();
    }

    // 약관 chunk 목록을 프론트에서 바로 사용할 evidences 배열로 변환
    private List<DocumentGuideResponse.EvidenceItem> buildEvidenceItems(List<TermsChunkInfo> evidenceChunks) {
        return filterEvidenceChunks(evidenceChunks).stream()
                .limit(3)
                .map(chunk -> DocumentGuideResponse.EvidenceItem.builder()
                        .chunkId(chunk.chunkId())
                        .title(getEvidenceTitle(chunk))
                        .build())
                .toList();
    }

    // 필요서류별로 프론트 카드에 표시할 간단한 설명을 생성
    private String buildDocumentDescription(String document) {
        return switch (document) {
            case "청구서(회사양식)" -> "보험사 양식에 맞춰 작성하는 보험금 청구서예요.";
            case "신분증" -> "보험금 청구자 본인 확인을 위한 서류예요.";
            case "재해 입증서류" -> "상해 또는 재해 사실을 확인하기 위한 서류예요.";
            case "진단서" -> "진단명과 진단 사실을 확인하기 위한 서류예요.";
            case "진단사실 확인서류 또는 검사결과지" -> "진단 근거가 되는 검사 결과를 확인하기 위한 서류예요.";
            case "수술 확인서" -> "수술명과 수술일자를 확인하기 위한 서류예요.";
            case "입·퇴원 확인서" -> "입원 기간과 입·퇴원 사실을 확인하기 위한 서류예요.";
            case "통원 확인서" -> "통원 치료 사실을 확인하기 위한 서류예요.";
            case "깁스(Cast) 치료 증명서" -> "깁스 치료 여부를 확인하기 위한 서류예요.";
            case "진료기록부" -> "치료 내용과 경과를 확인하기 위한 진료 기록이에요.";
            case "치과치료 확인서" -> "치과 치료 종류와 치료 내용을 확인하기 위한 서류예요.";
            case "치과진료기록" -> "치과 진료 내역을 확인하기 위한 기록이에요.";
            case "X-ray 사진 또는 구강 내 사진" -> "치아 치료 상태를 확인하기 위한 이미지 자료예요.";
            case "장해진단서" -> "장해 상태와 정도를 확인하기 위한 서류예요.";
            default -> "보험금 청구 심사에 필요할 수 있는 서류예요.";
        };
    }

    // 약관 chunk의 조항번호, 조항명, 섹션명을 조합해 화면에 보여줄 제목을 생성
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

        return "약관 근거";
    }

    // 필요서류 안내와 관련성이 낮은 약관 chunk인지 판단
    private boolean isIrrelevantEvidence(TermsChunkInfo chunk) {
        String title = getEvidenceTitle(chunk);
        String chunkText = chunk.chunkText();

        String target = (title + " " + chunkText);

        return target.contains("지정대리청구인")
                || target.contains("이 특약의 사망보험금")
                || target.contains("사망보험금을 청구");
    }

    // 관련성이 낮은 근거를 제거하되, 모두 제거되는 경우에는 원본 근거를 유지
    private List<TermsChunkInfo> filterEvidenceChunks(List<TermsChunkInfo> evidenceChunks) {
        List<TermsChunkInfo> filteredChunks = evidenceChunks.stream()
                .filter(chunk -> !isIrrelevantEvidence(chunk))
                .toList();

        if (filteredChunks.isEmpty()) {
            return evidenceChunks;
        }

        return filteredChunks;
    }

    // 약관 chunk 본문을 응답에 넣기 좋은 한 줄 문자열로 정리
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