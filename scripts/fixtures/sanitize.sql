-- =============================================
-- 회귀 테스트 픽스처를 공개 레포에 커밋하기 전 개인정보 제거
-- 대상: 로컬 시딩 DB(boda-pg). 이 스크립트는 시딩 DB를 직접 수정한다.
-- =============================================

-- 1) 증권 원문은 저장하지 않는다.
--    PdfExtractService.mask()는 라벨("계약번호" 등)이 붙은 값만 가리므로
--    OCR 텍스트에서는 이름·번호가 남을 수 있다.
UPDATE policy_analysis
SET masked_text = NULL,
    s3_key = NULL,
    original_file_name = 'policy.pdf';

-- 2) 증권 추출 정보는 화이트리스트 키만 남긴다. (insuredName 등 제거)
--    채팅 답변에서 읽는 키: insuranceStartDate
UPDATE policy_analysis
SET extracted_data = (
    SELECT COALESCE(jsonb_object_agg(key, value), '{}'::jsonb)
    FROM jsonb_each(extracted_data)
    WHERE key IN ('companyName', 'productName', 'insuranceStartDate', 'insuranceEndDate')
)
WHERE extracted_data IS NOT NULL;

-- 3) 약관 원문은 청크(terms_chunk)로 이미 저장돼 있고 채팅 답변에서 읽지 않으므로 비운다.
UPDATE terms_document
SET masked_text = NULL,
    s3_key = NULL,
    original_file_name = 'terms.pdf';

-- 4) 시딩 결과와 연결되지 않은 사용자 제거, 닉네임 고정
DELETE FROM users u
WHERE NOT EXISTS (SELECT 1 FROM policy_analysis p WHERE p.user_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM terms_document t WHERE t.user_id = u.id);

UPDATE users
SET nickname = 'fixture-user',
    profile_image_url = NULL;
