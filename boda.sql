-- =============================================
-- 보험생이 (BE_BODA) 최종 DDL v3
--
-- 설계 원칙:
-- 1. 증권 / 약관 / 채팅방 완전 독립 관리 (3개 리스트)
-- 2. 채팅방에서 증권(필수) + 약관(선택) 자유 조합
-- 3. 공통 컬럼 고정 + 가변 데이터 JSONB (PostgreSQL 특화)
-- 4. 특약 → 조항 → 청크 3단계 RAG 구조
-- 5. 증권: OCR 지원 (이미지/텍스트 PDF 모두)
-- 6. 약관: 텍스트 PDF 전용
-- =============================================


-- =============================================
-- 1. users
-- =============================================
CREATE TABLE users (
                       id                  BIGSERIAL       PRIMARY KEY,
                       kakao_id            BIGINT          NOT NULL UNIQUE,
                       nickname            VARCHAR(100)    NOT NULL,
                       profile_image_url   VARCHAR(1000)   NULL,
                       created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
                       updated_at          TIMESTAMP       NULL
);

COMMENT ON TABLE  users                   IS '카카오 소셜 로그인 사용자';
COMMENT ON COLUMN users.kakao_id          IS '카카오에서 발급한 고유 사용자 ID';
COMMENT ON COLUMN users.nickname          IS '카카오 프로필 닉네임';
COMMENT ON COLUMN users.profile_image_url IS '카카오 프로필 이미지 URL';


-- =============================================
-- 2. policy_analysis (증권 리스트)
--
-- users와 1:N — 한 사용자가 여러 증권 보유 가능
-- 증권은 독립적으로 관리 (약관/채팅방과 분리)
--
-- extracted_data JSONB 예시:
-- {
--   "companyName": "삼성생명",
--   "productName": "삼성생명 건강보험",
--   "contractorName": "홍길동",
--   "insuredName": "홍길동",
--   "insuranceStartDate": "2020-01-01",
--   "insuranceEndDate": "2040-01-01",
--   "monthlyPremium": 150000,
--   "policyNumber": "123456789"
-- }
-- =============================================
CREATE TABLE policy_analysis (
                                 analysis_id         BIGSERIAL       PRIMARY KEY,
                                 user_id             BIGINT          NOT NULL,

    -- 파일 정보
                                 original_file_name  VARCHAR(255)    NULL,
                                 s3_key              VARCHAR(500)    NULL,
                                 is_ocr              BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 마스킹된 텍스트
                                 masked_text         TEXT            NULL,

    -- LLM 추출 결과 (보험사마다 필드 다름 → JSONB)
                                 extracted_data      JSONB           NULL,

    -- 분석 상태
                                 analysis_status     VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
                                 error_message       TEXT            NULL,
                                 created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
                                 completed_at        TIMESTAMP       NULL,

                                 CONSTRAINT fk_policy_analysis_user
                                     FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE  policy_analysis                   IS '보험증권 분석 결과 (증권 리스트)';
COMMENT ON COLUMN policy_analysis.is_ocr            IS '이미지 PDF → OCR 사용 여부';
COMMENT ON COLUMN policy_analysis.masked_text       IS '민감정보 마스킹 후 보관하는 증권 텍스트';
COMMENT ON COLUMN policy_analysis.extracted_data    IS 'LLM 추출 결과 JSONB. 보험사별로 필드 다름';
COMMENT ON COLUMN policy_analysis.analysis_status   IS '분석 상태. PENDING/ANALYZING/DONE/ERROR';


-- =============================================
-- 3. coverage_item (보장 카드)
--
-- policy_analysis와 1:N (최대 6개)
-- 카드 타입별 세부 데이터는 JSONB detail에 저장
-- detail JSONB 예시:
-- 진단비:

--          coverage_type = "진단"
--          is_detected = true
--          exclusion_keywords = null
 --         {
           --   "items": [
           --     {
           --       "coverageName": "암진단",
           --       "amounts": [
           --         {
           --           "condition": "조건없음",
           --           "coverageAmount": 10000000
           --         }
           --       ]
           --     }
           --   ]
           -- }
-- 실손 :
--         coverage_type = "실손"
--         is_detected = true
--         exclusion_keywords = null
--         detail = {
--           "items": [
--             {
--               "coverageName": "실손 세대",
--               "amounts": [
--                 {
--                   "condition": "3세대 가입 확인",
--                   "coverageAmount": null
--                 }
--               ]
--             }
--           ]
--         }
-- =============================================
CREATE TABLE coverage_item (
                               coverage_id         BIGSERIAL       PRIMARY KEY,
                               analysis_id         BIGINT          NOT NULL,

                               coverage_type       VARCHAR(50)     NOT NULL,
                               is_detected         BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 공통 컬럼
                               exclusion_keywords  TEXT            NULL,
                               evidence_text       TEXT            NULL,        -- 약관 연결 후 채워짐

    -- 타입별 가변 데이터 (JSONB)
                               detail              JSONB           NULL,

                               created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                               CONSTRAINT fk_coverage_item_analysis
                                   FOREIGN KEY (analysis_id) REFERENCES policy_analysis(analysis_id)
);

COMMENT ON TABLE  coverage_item                     IS '보험 보장 항목 카드. 증권 분석 시 생성';
COMMENT ON COLUMN coverage_item.coverage_type       IS '진단/수술/입원/실손/골절재해/치아';
COMMENT ON COLUMN coverage_item.evidence_text       IS '약관 연결 후 RAG로 채워지는 근거 원문';
COMMENT ON COLUMN coverage_item.detail              IS '카드 타입별 세부 데이터 JSONB';


-- =============================================
-- 4. terms_document (약관 리스트)
--
-- users와 1:N — 한 사용자가 여러 약관 보유 가능
-- 증권과 독립적으로 관리 (user_id만 FK)
-- 약관은 텍스트 PDF 전용 (OCR 없음)
-- =============================================
CREATE TABLE terms_document (
                                terms_document_id   BIGSERIAL       PRIMARY KEY,
                                user_id             BIGINT          NOT NULL,

    -- 파일 정보
                                original_file_name  VARCHAR(255)    NULL,
                                s3_key              VARCHAR(500)    NULL,
                                company_name        VARCHAR(100)    NULL,
                                terms_title         VARCHAR(255)    NULL,

    -- 마스킹된 텍스트
                                masked_text         TEXT            NULL,

    -- 파싱 상태 (증권과 독립)
                                parsing_status      VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
                                error_message       TEXT            NULL,
                                created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                                CONSTRAINT fk_terms_document_user
                                    FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE  terms_document                    IS '약관 문서 (약관 리스트). 증권과 독립 관리';
COMMENT ON COLUMN terms_document.user_id            IS '업로드한 사용자. 증권과 무관하게 독립 관리';
COMMENT ON COLUMN terms_document.parsing_status     IS '파싱 상태. PENDING/ANALYZING/DONE/ERROR';


-- =============================================
-- 5. terms_rider (특약 단위)
-- =============================================
CREATE TABLE terms_rider (
                             rider_id            BIGSERIAL       PRIMARY KEY,
                             terms_document_id   BIGINT          NOT NULL,
                             rider_name          VARCHAR(255)    NOT NULL,
                             start_page          INT             NULL,
                             end_page            INT             NULL,

    -- 특약별 가변 메타데이터 (JSONB)
    -- 예: {"riderCode":"LT","coverageType":"진단비","premiumRate":0.003}
                             metadata            JSONB           NULL,

                             created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                             CONSTRAINT fk_terms_rider_document
                                 FOREIGN KEY (terms_document_id) REFERENCES terms_document(terms_document_id)
);

COMMENT ON TABLE  terms_rider               IS '약관 내 특약 단위';
COMMENT ON COLUMN terms_rider.rider_name    IS '특약명. 예: 재해추상골절·깁스치료비특약L';
COMMENT ON COLUMN terms_rider.metadata      IS '특약별 가변 메타데이터 JSONB';


-- =============================================
-- 6. terms_clause (조항 단위)
-- =============================================
CREATE TABLE terms_clause (
                              clause_id           BIGSERIAL       PRIMARY KEY,
                              rider_id            BIGINT          NOT NULL,
                              clause_no           VARCHAR(50)     NULL,
                              clause_title        VARCHAR(255)    NULL,
                              clause_type         VARCHAR(30)     NOT NULL,
                              parent_clause_id    BIGINT          NULL,
                              created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                              CONSTRAINT fk_terms_clause_rider
                                  FOREIGN KEY (rider_id) REFERENCES terms_rider(rider_id),
                              CONSTRAINT fk_terms_clause_parent
                                  FOREIGN KEY (parent_clause_id) REFERENCES terms_clause(clause_id)
);

COMMENT ON TABLE  terms_clause                  IS '약관 조항 단위';
COMMENT ON COLUMN terms_clause.clause_type      IS 'DEFINITION/PAYMENT_REASON/DETAIL_RULE/EXCLUSION/PERIOD/TABLE';
COMMENT ON COLUMN terms_clause.parent_clause_id IS '상위 조항 ID. 자기참조 (제2-1조의3 → 제2-1조)';


-- =============================================
-- 7. terms_chunk (청크 단위 — RAG 최소 단위)
-- =============================================
CREATE TABLE terms_chunk (
                             chunk_id            BIGSERIAL       PRIMARY KEY,
                             terms_document_id   BIGINT          NOT NULL,
                             rider_id            BIGINT          NULL,
                             clause_id           BIGINT          NULL,
                             chunk_index         INT             NOT NULL,
                             clause_type         VARCHAR(30)     NULL,
                             section_title       VARCHAR(255)    NULL,
                             chunk_text          TEXT            NOT NULL,
    -- embedding        vector(1536)    NULL,   pgvector 전환 시 주석 해제
                             created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                             CONSTRAINT fk_terms_chunk_document
                                 FOREIGN KEY (terms_document_id) REFERENCES terms_document(terms_document_id),
                             CONSTRAINT fk_terms_chunk_rider
                                 FOREIGN KEY (rider_id) REFERENCES terms_rider(rider_id),
                             CONSTRAINT fk_terms_chunk_clause
                                 FOREIGN KEY (clause_id) REFERENCES terms_clause(clause_id)
);

COMMENT ON TABLE  terms_chunk                   IS '약관 청크. RAG 검색 최소 단위';
COMMENT ON COLUMN terms_chunk.clause_type       IS '조항 유형 복사본. 검색 필터 성능 최적화';
COMMENT ON COLUMN terms_chunk.chunk_text        IS 'RAG 검색 대상 텍스트';


-- =============================================
-- 8. chat_session (채팅방 리스트)
--
-- 채팅방 생성 시 증권(필수) + 약관(선택) 자유 조합
-- GPT/Claude처럼 채팅방별 독립 세션 관리
-- =============================================
CREATE TABLE chat_session (
                              chat_session_id     BIGSERIAL       PRIMARY KEY,
                              user_id             BIGINT          NOT NULL,
                              analysis_id         BIGINT          NOT NULL,       -- 증권 필수
                              terms_document_id   BIGINT          NULL,           -- 약관 선택 (NULL 가능)
                              session_title       VARCHAR(255)    NULL,
                              created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
                              ended_at            TIMESTAMP       NULL,

                              CONSTRAINT fk_chat_session_user
                                  FOREIGN KEY (user_id) REFERENCES users(id),
                              CONSTRAINT fk_chat_session_analysis
                                  FOREIGN KEY (analysis_id) REFERENCES policy_analysis(analysis_id),
                              CONSTRAINT fk_chat_session_terms
                                  FOREIGN KEY (terms_document_id) REFERENCES terms_document(terms_document_id)
);

COMMENT ON TABLE  chat_session                       IS '채팅방 리스트. GPT처럼 채팅방별 독립 세션';
COMMENT ON COLUMN chat_session.analysis_id           IS '필수: 연결된 증권';
COMMENT ON COLUMN chat_session.terms_document_id     IS '선택: 연결된 약관. NULL이면 증권만으로 답변';


-- =============================================
-- 9. chat_message (채팅 메시지)
-- =============================================
CREATE TABLE chat_message (
                              message_id          BIGSERIAL       PRIMARY KEY,
                              chat_session_id     BIGINT          NOT NULL,
                              sender_type         VARCHAR(20)     NOT NULL,
                              question_type       VARCHAR(30)     NULL,
                              message_content     TEXT            NOT NULL,
                              used_fallback       BOOLEAN         NULL DEFAULT FALSE,
                              disclaimer_text     VARCHAR(255)    NULL,
                              created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

                              CONSTRAINT fk_chat_message_session
                                  FOREIGN KEY (chat_session_id) REFERENCES chat_session(chat_session_id)
);

COMMENT ON COLUMN chat_message.sender_type    IS '발신자. USER / AI';
COMMENT ON COLUMN chat_message.question_type  IS 'CHIP_CLAIM/CHIP_AMOUNT/CHIP_DOCUMENTS/CHIP_OVERVIEW/FREE_TEXT';
COMMENT ON COLUMN chat_message.used_fallback  IS 'Confidence 미달로 gpt-full 모델 사용 여부';


-- =============================================
-- 10. chat_message_source (AI 답변 근거)
-- =============================================
CREATE TABLE chat_message_source (
                                     source_id       BIGSERIAL       PRIMARY KEY,
                                     message_id      BIGINT          NOT NULL,
                                     chunk_id        BIGINT          NOT NULL,
                                     cited_text      TEXT            NULL,
                                     relevance_score DECIMAL(5,4)    NULL,
                                     created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

                                     CONSTRAINT fk_source_message
                                         FOREIGN KEY (message_id) REFERENCES chat_message(message_id),
                                     CONSTRAINT fk_source_chunk
                                         FOREIGN KEY (chunk_id) REFERENCES terms_chunk(chunk_id)
);

COMMENT ON TABLE  chat_message_source                 IS 'AI 답변에 사용된 RAG 근거 청크';
COMMENT ON COLUMN chat_message_source.relevance_score IS '코사인 유사도 점수 (0.0~1.0)';


-- =============================================
-- 인덱스
-- =============================================

-- users
CREATE INDEX idx_users_kakao_id                 ON users(kakao_id);

-- policy_analysis (증권)
CREATE INDEX idx_policy_analysis_user_id        ON policy_analysis(user_id);
CREATE INDEX idx_policy_analysis_status         ON policy_analysis(analysis_status);
CREATE INDEX idx_policy_analysis_extracted      ON policy_analysis USING gin(extracted_data);

-- coverage_item
CREATE INDEX idx_coverage_item_analysis_id      ON coverage_item(analysis_id);
CREATE INDEX idx_coverage_item_coverage_type    ON coverage_item(coverage_type);
CREATE INDEX idx_coverage_item_detail           ON coverage_item USING gin(detail);

-- terms_document (약관)
CREATE INDEX idx_terms_document_user_id         ON terms_document(user_id);
CREATE INDEX idx_terms_document_status          ON terms_document(parsing_status);

-- terms_rider
CREATE INDEX idx_terms_rider_document_id        ON terms_rider(terms_document_id);

-- terms_clause
CREATE INDEX idx_terms_clause_rider_id          ON terms_clause(rider_id);
CREATE INDEX idx_terms_clause_type              ON terms_clause(clause_type);

-- terms_chunk
CREATE INDEX idx_terms_chunk_document_id        ON terms_chunk(terms_document_id);
CREATE INDEX idx_terms_chunk_rider_id           ON terms_chunk(rider_id);
CREATE INDEX idx_terms_chunk_clause_type        ON terms_chunk(clause_type);

-- chat_session (채팅방)
CREATE INDEX idx_chat_session_user_id           ON chat_session(user_id);
CREATE INDEX idx_chat_session_analysis_id       ON chat_session(analysis_id);
CREATE INDEX idx_chat_session_terms_id          ON chat_session(terms_document_id);

-- chat_message
CREATE INDEX idx_chat_message_session_id        ON chat_message(chat_session_id);