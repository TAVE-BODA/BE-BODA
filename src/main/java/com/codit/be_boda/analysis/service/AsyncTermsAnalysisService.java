package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.*;
import com.codit.be_boda.analysis.repository.*;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.global.support.InsurerNameResolver;
import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.upload.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 약관 비동기 파싱 전용 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTermsAnalysisService {

    private final TermsDocumentRepository termsDocumentRepository;
    private final TermsRiderRepository termsRiderRepository;
    private final TermsClauseRepository termsClauseRepository;
    private final TermsChunkRepository termsChunkRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final S3Service s3Service;
    private final RagService ragService;
    private final InsurerNameResolver insurerNameResolver;

    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("(제\\s*\\d+(?:-\\d+)?조(?:의\\d+)?)\\s*[\\(（]([^\\)）]{1,50})[\\)）]");

    private static final Pattern RIDER_PATTERN =
            Pattern.compile("([가-힣]+(?:특약|특칙)[가-힣A-Za-z0-9Ⅰ-Ⅹ]*L?T?)");

    @Async
    @Transactional
    public void parseAsync(TermsDocument doc, String maskedText,
                           Map<Integer, String> pageTexts, Long chatSessionId) {
        long start = System.currentTimeMillis();
        log.info("[TERMS] 약관 비동기 파싱 시작 | termsId={}", doc.getId());
        doc.startParsing();
        termsDocumentRepository.save(doc);

        try {
            // 1. 특약 분리 → TermsRider 저장
            List<RiderSection> riderSections = parseRiders(maskedText);
            log.info("[TERMS] 특약 감지 | 특약수={}", riderSections.size());

            for (RiderSection section : riderSections) {
                TermsRider rider = TermsRider.builder()
                        .termsDocument(doc)
                        .riderName(section.name())
                        .startPage(null)
                        .endPage(null)
                        .build();
                termsRiderRepository.save(rider);

                // 2. 조항 파싱 → TermsClause + TermsChunk 저장
                parseClauses(doc, rider, section.text(), pageTexts);
            }

            // 3. RAG 임베딩 인덱싱
            ragService.indexTerms(doc.getId(), maskedText);
            log.info("[TERMS] RAG 인덱싱 완료 | {}ms", System.currentTimeMillis() - start);

            // 4. 채팅방 약관 연결
            if (chatSessionId != null) {
                chatSessionRepository.findById(chatSessionId).ifPresent(chatSession -> {
                    chatSession.updateTermsDocument(doc.getId());
                    chatSessionRepository.save(chatSession);
                    log.info("[TERMS] 채팅방 약관 연결 완료 | chatSessionId={} termsId={}",
                            chatSessionId, doc.getId());
                });
            }

            // 5. S3 원본 파기
            s3Service.deleteFile(doc.getS3Key());
            // 약관 본문에서 보험사명 추출
            // 마이페이지에서 "약관이 있으면 약관 기준으로 보험사 확정" 규칙에 사용된다
            String detectedCompany = insurerNameResolver.detectFromText(maskedText);
            log.info("[TERMS] 약관 보험사 추출 | termsId={} company={}", doc.getId(), detectedCompany);
            doc.completeParsing(detectedCompany, null, maskedText);
            termsDocumentRepository.save(doc);

            log.info("[TERMS] 약관 파싱 전체 완료 | 총{}ms", System.currentTimeMillis() - start);

        } catch (Exception e) {
            doc.failParsing(e.getMessage());
            termsDocumentRepository.save(doc);
            log.error("[TERMS] 약관 파싱 실패 | {}", e.getMessage(), e);
        }
    }

    private List<RiderSection> parseRiders(String text) {
        // 특약명 패턴으로 분리 (실제 약관은 특약명이 줄서로 시작)
        // 예: "\n장해진단특약 LT\n", "\n암진단특약 L10\n"
        String[] parts = text.split("(?=\n[\uAC00-\uD7A3]+(?:특약|특칙)[\uAC00-\uD7A3A-Za-z0-9Ⅰ-Ⅹ\s]*(?:L|LT|T)?\n)");

        if (parts.length <= 1) {
            // 특약 분리 안 되면 전체를 주계약으로
            return List.of(new RiderSection("주계약", text));
        }

        List<RiderSection> sections = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.trim().length() < 50) continue;
            // 첫 줄을 특약명으로
            String firstLine = part.strip().split("\n")[0].trim();
            String name = firstLine.isBlank() ? "주계약" : firstLine;
            sections.add(new RiderSection(name, part.trim()));
        }

        return sections.isEmpty()
                ? List.of(new RiderSection("주계약", text))
                : sections;
    }

    private void parseClauses(TermsDocument doc, TermsRider rider, String text,
                               Map<Integer, String> pageTexts) {
        String[] clauseParts = text.split("(?=제\\s*\\d+(?:-\\d+)?조)");
        int chunkIndex = 0;

        List<TermsChunk> chunkBatch = new java.util.ArrayList<>();

        for (String part : clauseParts) {
            if (part.trim().length() < 20) continue;

            Matcher m = CLAUSE_PATTERN.matcher(part);
            String clauseNo = null, clauseTitle = null;
            if (m.find()) {
                clauseNo = m.group(1).replaceAll("\\s+", "");
                clauseTitle = m.group(2).trim();
            }

            String clauseType = detectClauseType(part);

            TermsClause clause = TermsClause.builder()
                    .termsRider(rider)
                    .clauseNo(clauseNo)
                    .clauseTitle(clauseTitle)
                    .clauseType(clauseType)
                    .parentClause(null)
                    .build();
            termsClauseRepository.save(clause);

            List<String> chunks = splitToChunks(part, 800, 100);
            for (String chunkText : chunks) {
                // 페이지 번호 추적: pageTexts에서 청크 텍스트가 포함된 페이지 찾기
                Integer pageNumber = findPageNumber(chunkText, pageTexts);

                chunkBatch.add(TermsChunk.builder()
                        .termsDocument(doc)
                        .termsRider(rider)
                        .termsClause(clause)
                        .chunkIndex(chunkIndex++)
                        .clauseType(clauseType)
                        .sectionTitle(clauseTitle)
                        .chunkText(chunkText)
                        .pageNumber(pageNumber)
                        .build());
            }
        }

        if (!chunkBatch.isEmpty()) {
            termsChunkRepository.saveAll(chunkBatch);
            log.info("[TERMS] 청크 배치 저장 | 청크수={}", chunkBatch.size());
        }
    }

    //청크 텍스트가 포함된 페이지 번호를 찾음
    //청크의 앞부분 20자를 페이지 텍스트에서 검색.
    private Integer findPageNumber(String chunkText, Map<Integer, String> pageTexts) {
        if (pageTexts == null || pageTexts.isEmpty()) return null;

        // 청크 시작 20자를 키로 페이지 검색
        String searchKey = chunkText.trim().length() > 20
                ? chunkText.trim().substring(0, 20)
                : chunkText.trim();

        for (Map.Entry<Integer, String> entry : pageTexts.entrySet()) {
            if (entry.getValue().contains(searchKey)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String detectClauseType(String text) {
        if (text.contains("용어의 정의") || text.contains("\"") || text.contains("이란"))
            return "DEFINITION";
        if (text.contains("지급사유") || text.contains("지급하는 경우") || text.contains("보험금을 드립니다"))
            return "PAYMENT_REASON";
        if (text.contains("지급하지 않") || text.contains("면책") || text.contains("해당되지 않"))
            return "EXCLUSION";
        if (text.contains("보험기간") || text.contains("계약일") || text.contains("만기"))
            return "PERIOD";
        if (text.contains("별표") || text.contains("분류표") || text.contains("【표】"))
            return "TABLE";
        return "DETAIL_RULE";
    }

    private List<String> splitToChunks(String text, int size, int overlap) {
        List<String> result = new java.util.ArrayList<>();
        int step = size - overlap;
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return result;
    }

    private record RiderSection(String name, String text) {}
}
