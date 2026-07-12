package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.*;
import com.codit.be_boda.analysis.repository.*;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

 // 보험약관 분석 서비스
 // 1. TermsDocument 레코드 생성
 // 2. @Async로 비동기 파싱 시작
 // 3. 특약 경계 감지 → TermsRider 저장
 // 4. 조항 단위 파싱 → TermsClause 저장
 // 5. 청크 분리 → TermsChunk 저장
 // 6. RagService 임베딩 인덱싱
 // 7. S3 원본 파기  .. (주석처리 22)
@Slf4j
@Service
@RequiredArgsConstructor
public class TermsAnalysisService {

    private final TermsDocumentRepository termsDocumentRepository;
    private final TermsRiderRepository termsRiderRepository;
    private final TermsClauseRepository termsClauseRepository;
    private final TermsChunkRepository termsChunkRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final S3Service s3Service;
    private final RagService ragService;

    // 조항 번호 패턴: 제2조, 제2-1조, 제2-1조의3
     //TODO 조항, 특약 경계 패턴 구조화 부족 (후순위 작업으로 진행)
    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("(제\\s*\\d+(?:-\\d+)?조(?:의\\d+)?)\\s*[\\[【]([^\\]】]+)[\\]】]");

    // 특약 경계 패턴
    private static final Pattern RIDER_PATTERN =
            Pattern.compile("([가-힣]+(?:특약|특칙)[가-힣A-Za-z0-9Ⅰ-Ⅹ]*L?T?)");

    @Transactional
    public TermsDocument createAndStartParsing(User user, String originalFileName,
                                               String s3Key, String maskedText,
                                               Long chatSessionId) {
        TermsDocument doc = TermsDocument.builder()
                .user(user)
                .originalFileName(originalFileName)
                .s3Key(s3Key)
                .build();

        termsDocumentRepository.save(doc);
        log.info("[TERMS] 약관 레코드 생성 | termsId={}", doc.getId());

        parseAsync(doc, maskedText, chatSessionId);
        return doc;
    }

    @Async
    @Transactional
    public void parseAsync(TermsDocument doc, String maskedText, Long chatSessionId) {
        long start = System.currentTimeMillis();
        log.info("[TERMS] 약관 비동기 파싱 시작 | termsId={}", doc.getId());
        doc.startParsing();
        termsDocumentRepository.save(doc);

        try {
            //1. 특약 분리 ->  TermsRider 저장
            //TODO 특약항목 아마 더 보고.. 더미데이터화 하기
            List<RiderSection> riderSections = parseRiders(maskedText);
            log.info("[TERMS] 특약 감지 | 특약수={}", riderSections.size());

            for (RiderSection section : riderSections) {
                TermsRider rider = TermsRider.builder()
                        .termsDocument(doc)
                        .riderName(section.name())
                        .startPage(null)    // 텍스트 기반 파싱 시 페이지 미지원
                        .endPage(null)
                        .build();
                termsRiderRepository.save(rider);

                //2. 조항 파싱 → TermsClause + TermsChunk 저장
                parseClauses(doc, rider, section.text());
            }

            // 3. RagService 임베딩 인덱싱
            ragService.indexTerms(doc.getId(), maskedText);
            log.info("[TERMS] RAG 인덱싱 완료 | {}ms", System.currentTimeMillis() - start);

            // 채팅방 연결 (코드3 플로우: 업로드 시 chatSessionId 포함 시 chat_session.terms_document_id 업데이트)
            if (chatSessionId != null) {
                chatSessionRepository.findById(chatSessionId).ifPresent(chatSession -> {
                    chatSession.updateTermsDocument(doc.getId());
                    chatSessionRepository.save(chatSession);
                    log.info("[TERMS] 채팅방 약관 연결 완료 | chatSessionId={} termsId={}",
                            chatSessionId, doc.getId());
                });
            }

            // S3 원본 파기
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

    //새 보험사 약관 형식 추가 시 패턴 추가
    private List<RiderSection> parseRiders(String text) {
        // 특약명 패턴으로 분리 시도
        String[] parts = text.split("(?=(?:제\\s*1\\s*편|제\\s*2\\s*편|제\\s*3\\s*편))");

        if (parts.length <= 1) {
            // 편 구분 없는 경우 — 전체를 하나의 특약으로 처리
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

    //조항 단위 파싱 및 청크 저장
    private void parseClauses(TermsDocument doc, TermsRider rider, String text) {
        String[] clauseParts = text.split("(?=제\\s*\\d+(?:-\\d+)?조)");
        int chunkIndex = 0;

        for (String part : clauseParts) {
            if (part.trim().length() < 20) continue;

            // 조항 정보 파싱
            Matcher m = CLAUSE_PATTERN.matcher(part);
            String clauseNo = null, clauseTitle = null;
            if (m.find()) {
                clauseNo = m.group(1).replaceAll("\\s+", "");
                clauseTitle = m.group(2).trim();
            }

            String clauseType = detectClauseType(part);

            // TermsClause 저장
            TermsClause clause = TermsClause.builder()
                    .termsRider(rider)
                    .clauseNo(clauseNo)
                    .clauseTitle(clauseTitle)
                    .clauseType(clauseType)
                    .parentClause(null)
                    .build();
            termsClauseRepository.save(clause);

            // TermsChunk 저장 (800자 기준 슬라이딩 윈도우)
            //TODO 이 부분도 적절한 값을 찾아 내야 함.. 800자가 적절할지 의문. BUT.. 너무 자른다면 성능 이슈가 있다..
            List<String> chunks = splitToChunks(part, 800, 100);
            for (String chunkText : chunks) {
                TermsChunk chunk = TermsChunk.builder()
                        .termsDocument(doc)
                        .termsRider(rider)
                        .termsClause(clause)
                        .chunkIndex(chunkIndex++)
                        .clauseType(clauseType)
                        .sectionTitle(clauseTitle)
                        .chunkText(chunkText)
                        .build();
                termsChunkRepository.save(chunk);
            }
        }
    }

    //조항 유형 자동 감지
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

    //슬라이딩 윈도우 청킹
    private List<String> splitToChunks(String text, int size, int overlap) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int step = size - overlap;
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return result;
    }

    private record RiderSection(String name, String text) {}
}
