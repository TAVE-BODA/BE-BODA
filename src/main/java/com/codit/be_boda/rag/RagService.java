package com.codit.be_boda.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

//RAG 파이프라인
// 약관 PDF 텍스트를 조각내어 벡터로 변환 저장하고, 질문이 들어오면 가장 관련 있는 조각을 꺼내 반환하는 파이프라인
// [오프라인] 약관 → 조항 단위 청킹 → store.add() (내부 배치 임베딩)
// [실시간]  질문 → 유사도 검색 → 상위 K개 청크 반환

//[실서비스 전환 시] SimpleVectorStore → PgVectorStore 교체
//TODO (4) : 청크 배분 적정 사이즈 찾기
//TODO (5) : 음.. 이 부분 공부 좀 더 필요. (전문 지식 부족..)
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final boolean mockMode;

    @Value("${app.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    @Value("${app.rag.top-k:8}")
    private int topK;

    // 세션별 독립 VectorStore
    private final Map<String, SimpleVectorStore> storeMap = new HashMap<>();

    public RagService(EmbeddingModel embeddingModel,
                      @Value("${app.mock-mode:false}") boolean mockMode) {
        this.embeddingModel = embeddingModel;
        this.mockMode = mockMode;
    }

    //약관 인덱싱
    public void indexTerms(String userId, String termsText) {
        if (mockMode) return;

        long t = System.currentTimeMillis();
        List<Document> chunks = chunkByArticle(termsText, userId);
        log.info("[RAG] 청킹 완료 | 청크={} | {}ms", chunks.size(), System.currentTimeMillis() - t);

        t = System.currentTimeMillis();
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(chunks);
        storeMap.put(userId, store);
        log.info("[RAG] 임베딩+저장 완료 | {}ms", System.currentTimeMillis() - t);
    }

    // 유사 청크 검색
    // 질문시 마다 해당 함수 실행.
    // TODO :
    public List<String> search(String userId, String query) {
        if (mockMode) return List.of(
            "[mock] 제3조 (보장) 입원 시 1일당 50,000원 지급",
            "[mock] 제15조 (면책) 정신과, 치과 제외"
        );

        SimpleVectorStore store = storeMap.get(userId);
        if (store == null) return List.of();

        long t = System.currentTimeMillis();
        List<String> results = store.similaritySearch(
            SearchRequest.builder().query(query).topK(topK).build()
        ).stream().map(Document::getText).toList();

        log.info("[RAG] 검색 완료 | 결과={} | {}ms", results.size(), System.currentTimeMillis() - t);
        results.forEach((r) -> log.info("[RAG] 청크: {}...",
            r.length() > 80 ? r.substring(0, 80).replace("\n", " ") : r));
        return results;
    }

    public boolean hasIndex(String userId) {
        return storeMap.containsKey(userId);
    }

    // 청킹 핵심
    // TODO :
    private List<Document> chunkByArticle(String text, String userId) {
        List<Document> chunks = new ArrayList<>();
        String[] articles = Pattern.compile("(?=제\\s*\\d+\\s*조)").split(text);

        for (int i = 0; i < articles.length; i++) {
            String a = articles[i].trim();
            if (a.length() < 30) continue;
            if (a.length() <= chunkSize) {
                chunks.add(doc(a, userId, i));
            } else {
                List<String> sub = slide(a);
                for (int j = 0; j < sub.size(); j++) chunks.add(doc(sub.get(j), userId, i * 1000 + j));
            }
        }
        if (chunks.isEmpty()) {
            List<String> wins = slide(text);
            for (int i = 0; i < wins.size(); i++) chunks.add(doc(wins.get(i), userId, i));
        }
        return chunks;
    }

    // 조항 800자 넘으면 추가 분할.
    private List<String> slide(String text) {
        List<String> r = new ArrayList<>();
        int step = chunkOverlap == 0 ? chunkSize : chunkSize - chunkOverlap;
        for (int s = 0; s < text.length(); s += step)
            r.add(text.substring(s, Math.min(s + chunkSize, text.length())));
        return r;
    }

    private Document doc(String content, String userId, int idx) {
        return new Document(content, Map.of("userId", userId, "chunkIndex", idx));
    }
}
