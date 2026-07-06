package com.codit.be_boda.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

//RAG 파이프라인
 //약관 텍스트 → 청킹 → 임베딩 → SimpleVectorStore 저장
//실서비스 전환 시: SimpleVectorStore → PgVectorStore 교체
// 청킹 전략:
 //1. 제N조 패턴으로 조항 단위 분리
//2. 조항이 800자 초과 시 슬라이딩 윈도우(800자, 100자 overlap)로 추가 분할
//3. 임베딩 API 토큰 한도(8192) 초과 방지..
@Slf4j
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;

    @Value("${app.rag.top-k:8}")
    private int topK;

    // 청크 최대 크기 (한국어 1자 ≈ 1~1.5토큰, 800자 ≈ 800~1200토큰)
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 100;

    // 약관 문서 ID → VectorStore 맵
    private final Map<Long, SimpleVectorStore> storeMap = new HashMap<>();

    public RagService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 약관 텍스트 임베딩 인덱싱
     * @param termsDocumentId 약관 문서 ID (독립 관리 키)
     */
    public void indexTerms(Long termsDocumentId, String maskedText) {
        long t = System.currentTimeMillis();
        log.info("[RAG] 인덱싱 시작 | termsId={} | 텍스트길이={}", termsDocumentId, maskedText.length());

        List<Document> chunks = chunkText(maskedText, termsDocumentId);
        log.info("[RAG] 청킹 완료 | 청크수={}", chunks.size());

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(chunks);
        storeMap.put(termsDocumentId, store);

        log.info("[RAG] 인덱싱 완료 | {}ms", System.currentTimeMillis() - t);
    }

// 유사 청크 검색.
    public List<String> search(Long termsDocumentId, String query) {
        SimpleVectorStore store = storeMap.get(termsDocumentId);
        if (store == null) {
            log.warn("[RAG] 인덱스 없음 | termsId={}", termsDocumentId);
            return List.of();
        }

        long t = System.currentTimeMillis();
        List<String> results = store.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build()
        ).stream().map(Document::getText).toList();

        log.info("[RAG] 검색 완료 | 결과={}개 | {}ms", results.size(), System.currentTimeMillis() - t);
        results.forEach(r -> log.debug("[RAG] 청크: {}...",
                r.length() > 80 ? r.substring(0, 80).replace("\n", " ") : r));

        return results;
    }

    public boolean hasIndex(Long termsDocumentId) {
        return storeMap.containsKey(termsDocumentId);
    }

    //청킹
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
