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
// 약관 텍스트 -> 임베딩 -> SimpleVectorStore 저장
// 실 서비스에는 SimpleVectorStore -> PgVectorStore 로 교체
//(VectorStore 인터페이스 통해 호출하므로 이 파일만 수정)
@Slf4j
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;

    @Value("${app.rag.top-k:8}")
    private int topK;

    // 약관 문서 ID -> VectorStore 맵
    private final Map<Long, SimpleVectorStore> storeMap = new HashMap<>();

    public RagService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    //약관 텍스트 임베딩 인덱싱
    // @param termsDocumentId 약관 문서 ID (독립 관리 키)
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

    // 유사 청크 검색
    // @param termsDocumentId 약관 문서 ID
    // @param query 검색 질문
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

    private List<Document> chunkText(String text, Long termsDocumentId) {
        List<Document> chunks = new ArrayList<>();
        String[] articles = text.split("(?=제\\s*\\d+\\s*조)");
        int idx = 0;

        for (String article : articles) {
            String trimmed = article.trim();
            if (trimmed.length() < 30) continue;
            chunks.add(new Document(trimmed, Map.of(
                    "termsDocumentId", termsDocumentId,
                    "chunkIndex", idx++
            )));
        }

        if (chunks.isEmpty()) {
            // 조항 패턴 없는 경우 슬라이딩 윈도우
            for (int i = 0; i < text.length(); i += 700) {
                String sub = text.substring(i, Math.min(i + 800, text.length()));
                chunks.add(new Document(sub, Map.of(
                        "termsDocumentId", termsDocumentId,
                        "chunkIndex", idx++
                )));
            }
        }
        return chunks;
    }
}
