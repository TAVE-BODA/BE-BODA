package com.codit.be_boda.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

// RAG 파이프라인
//   약관 텍스트 → 청킹 → 임베딩 → PgVectorStore(RDS pgvector) 영속 저장
//   SimpleVectorStore(JVM 메모리) → PgVectorStore 전환 완료
//   저장소는 VectorStore 인터페이스로만 다루므로 검색/인덱싱 호출부(챗봇 등)는 그대로 유지됨
// 청킹 전략:
//   1. 제N조 패턴으로 조항 단위 분리
//   2. 조항이 800자 초과 시 슬라이딩 윈도우(800자, 100자 overlap)로 추가 분할
//   3. 임베딩 API 토큰 한도(8192) 초과 방지
@Slf4j
@Service
public class RagService {

    // PgVectorStore 자동설정 빈이 주입됨 (구체 클래스 아닌 인터페이스 의존)
    private final VectorStore vectorStore;
    // hasIndex 카운트 조회용 (vector_store 테이블 직접 조회)
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.rag.top-k:8}")
    private int topK;

    // 청크 최대 크기 (한국어 1자 ≈ 1~1.5토큰, 800자 ≈ 800~1200토큰)
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;

    public RagService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    // 약관 텍스트 임베딩 인덱싱 (RDS pgvector 영속 저장)
    // 동일 termsDocumentId 재인덱싱 시 기존 벡터 삭제 후 재적재 (멱등성)
    public void indexTerms(Long termsDocumentId, String maskedText) {
        long t = System.currentTimeMillis();
        log.info("[RAG] 인덱싱 시작 | termsId={} | 텍스트길이={}", termsDocumentId, maskedText.length());

        List<Document> chunks = chunkText(maskedText, termsDocumentId);
        log.info("[RAG] 청킹 완료 | 청크수={}", chunks.size());

        // 멱등성: 동일 약관의 기존 벡터를 지우고 재적재 (재시도/재분석 시 중복 방지)
        deleteByTermsDocumentId(termsDocumentId);

        vectorStore.add(chunks); // 임베딩 생성 + RDS 저장을 PgVectorStore가 내부 처리

        log.info("[RAG] 인덱싱 완료 | {}ms", System.currentTimeMillis() - t);
    }

    // 유사 청크 검색 — 해당 약관(termsDocumentId)의 청크로만 필터링 (사용자/세션 격리)
    public List<String> search(Long termsDocumentId, String query) {
        long t = System.currentTimeMillis();

        var filter = new FilterExpressionBuilder()
                .eq("termsDocumentId", termsDocumentId)
                .build();

        List<Document> found = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .filterExpression(filter)
                        .build()
        );

        if (found == null || found.isEmpty()) {
            log.warn("[RAG] 검색 결과 없음 | termsId={}", termsDocumentId);
            return List.of();
        }

        List<String> results = found.stream().map(Document::getText).toList();

        log.info("[RAG] 검색 완료 | 결과={}개 | {}ms", results.size(), System.currentTimeMillis() - t);
        results.forEach(r -> log.debug("[RAG] 청크: {}...",
                r.length() > 80 ? r.substring(0, 80).replace("\n", " ") : r));

        return results;
    }

    // 해당 약관이 이미 인덱싱되어 있는지 (RDS vector_store 조회)
    public boolean hasIndex(Long termsDocumentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'termsDocumentId' = ?",
                Integer.class, String.valueOf(termsDocumentId));
        return count != null && count > 0;
    }

    // 약관 삭제 시 해당 약관의 벡터 인덱스 제거 (외부 호출용)
    public void deleteIndex(Long termsDocumentId) {
        deleteByTermsDocumentId(termsDocumentId);
        log.info("[RAG] 벡터 인덱스 삭제 | termsId={}", termsDocumentId);
    }

    // 동일 약관 벡터 삭제 (메타데이터 필터 기반)
    private void deleteByTermsDocumentId(Long termsDocumentId) {
        try {
            vectorStore.delete(
                    new FilterExpressionBuilder().eq("termsDocumentId", termsDocumentId).build());
        } catch (Exception e) {
            log.warn("[RAG] 기존 벡터 삭제 스킵 | termsId={} | {}", termsDocumentId, e.getMessage());
        }
    }

    // 청킹
    private List<Document> chunkText(String text, Long termsDocumentId) {
        List<Document> chunks = new ArrayList<>();
        String[] articles = text.split("(?=제\\s*\\d+\\s*조)");
        int idx = 0;

        for (String article : articles) {
            String trimmed = article.trim();
            if (trimmed.length() < 30) continue;

            if (trimmed.length() <= CHUNK_SIZE) {
                // CHUNK_SIZE 이하 → 그대로 저장
                chunks.add(doc(trimmed, termsDocumentId, idx++));
            } else {
                // CHUNK_SIZE 초과 → 슬라이딩 윈도우로 추가 분할
                // 임베딩 API 토큰 한도(8192) 초과 방지
                log.debug("[RAG] 조항 길이 초과 → 슬라이딩 윈도우 분할 | 길이={}", trimmed.length());
                for (String sub : slideWindow(trimmed)) {
                    chunks.add(doc(sub, termsDocumentId, idx++));
                }
            }
        }

        // 조항 패턴 없는 경우 → 전체 텍스트 슬라이딩 윈도우
        if (chunks.isEmpty()) {
            log.debug("[RAG] 조항 패턴 없음 → 전체 슬라이딩 윈도우");
            for (String sub : slideWindow(text)) {
                chunks.add(doc(sub, termsDocumentId, idx++));
            }
        }

        return chunks;
    }

    private List<String> slideWindow(String text) {
        List<String> result = new ArrayList<>();
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(i + CHUNK_SIZE, text.length())));
        }
        return result;
    }

    private Document doc(String content, Long termsDocumentId, int idx) {
        return new Document(content, Map.of(
                "termsDocumentId", termsDocumentId,
                "chunkIndex", idx
        ));
    }
}
