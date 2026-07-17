package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

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
    private final AsyncTermsAnalysisService asyncTermsAnalysisService;
    private final S3Service s3Service;

    @Transactional
    public TermsDocument createAndStartParsing(User user, String originalFileName,
                                               String s3Key, String maskedText,
                                               Map<Integer, String> pageTexts,
                                               Long chatSessionId) {
        TermsDocument doc = TermsDocument.builder()
                .user(user)
                .originalFileName(originalFileName)
                .s3Key(s3Key)
                .build();

        termsDocumentRepository.save(doc);
        log.info("[TERMS] 약관 레코드 생성 | termsId={}", doc.getId());

        // 별도 클래스 호출 → Spring AOP 프록시 경유 → @Async 정상 동작
        asyncTermsAnalysisService.parseAsync(doc, maskedText, pageTexts, chatSessionId);
        return doc;
    }
}
