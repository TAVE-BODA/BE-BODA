package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.*;
import com.codit.be_boda.analysis.repository.*;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.upload.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("(제\\s*\\d+(?:-\\d+)?조(?:의\\d+)?)\\s*[\\[【]([^\\]】]+)[\\]】]");

    private static final Pattern RIDER_PATTERN =
            Pattern.compile("([가-힣]+(?:특약|특칙)[가-힣A-Za-z0-9Ⅰ-Ⅹ]*L?T?)");

    @Async
    @Transactional
    public void parseAsync(TermsDocument doc, String maskedText, Long chatSessionId) {
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
                parseClauses(doc, rider, section.text());
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
            doc.completeParsing(null, null, maskedText);
            termsDocumentRepository.save(doc);

            log.info("[TERMS] 약관 파싱 전체 완료 | 총{}ms", System.currentTimeMillis() - start);

        } catch (Exception e) {
            doc.failParsing(e.getMessage());
            termsDocumentRepository.save(doc);
            log.error("[TERMS] 약관 파싱 실패 | {}", e.getMessage(), e);
        }
    }

    private List<RiderSection> parseRiders(String text) {
        String[] parts = text.split("(?=(?:제\\s*1\\s*편|제\\s*2\\s*편|제\\s*3\\s*편))");

        if (parts.length <= 1) {
            return List.of(new RiderSection("주계약", text));
        }

        return java.util.Arrays.stream(parts)
                .filter(p -> p.trim().length() > 50)
                .map(p -> {
                    Matcher m = RIDER_PATTERN.matcher(p);
                    String name = m.find() ? m.group(1) : "조항" + (int)(Math.random() * 100);
                    return new RiderSection(name, p.trim());
                })
                .toList();
    }

    private void parseClauses(TermsDocument doc, TermsRider rider, String text) {
        String[] clauseParts = text.split("(?=제\\s*\\d+(?:-\\d+)?조)");
        int chunkIndex = 0;

        // 청크를 배치로 모아서 한 번에 저장 (2순위 개선사항 반영)
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
                chunkBatch.add(TermsChunk.builder()
                        .termsDocument(doc)
                        .termsRider(rider)
                        .termsClause(clause)
                        .chunkIndex(chunkIndex++)
                        .clauseType(clauseType)
                        .sectionTitle(clauseTitle)
                        .chunkText(chunkText)
                        .build());
            }
        }

        // 배치 저장 (DB 왕복 횟수 대폭 감소)
        if (!chunkBatch.isEmpty()) {
            termsChunkRepository.saveAll(chunkBatch);
            log.info("[TERMS] 청크 배치 저장 | 청크수={}", chunkBatch.size());
        }
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
