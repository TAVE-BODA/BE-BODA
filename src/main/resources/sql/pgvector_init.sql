-- 1) 확장 설치
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2) 벡터 저장 테이블 (Spring AI PgVectorStore 기본 스키마와 동일)
-- id: UUID (Spring AI Document 기본 PK)
-- content: 임베딩된 원문 청크
-- metadata: {"termsDocumentId": N, "chunkIndex": M}
-- embedding: text-embedding-3-small → 1536차원
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1536)
);

-- 3) 벡터 유사도 인덱스 (코사인)
-- HNSW는 pgvector 0.5.0+ 필요. 버전이 낮으면 아래 ivfflat 사용:
-- CREATE INDEX ON vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- 4) 약관별 메타데이터 필터 조회 성능용 (세션 스코프 검색 / hasIndex 카운트)
CREATE INDEX IF NOT EXISTS idx_vector_store_terms_doc
    ON vector_store ((metadata->>'termsDocumentId'));
