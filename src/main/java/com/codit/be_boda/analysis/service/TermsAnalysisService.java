package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final ChatSessionRepository chatSessionRepository;
    private final AsyncTermsAnalysisService asyncTermsAnalysisService;
    private final RagService ragService;
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

    //약관 삭제 (soft delete)
    @Transactional
    public void deleteTerms(Long termsDocumentId, Long userId) {
        TermsDocument doc = termsDocumentRepository.findById(termsDocumentId)
                .orElseThrow(() -> new IllegalArgumentException("약관을 찾을 수 없어요."));

        if (!doc.getUser().getId().equals(userId)) {
            throw new SecurityException("본인의 약관만 삭제할 수 있어요.");
        }

        if (doc.isDeleted()) {
            log.info("[TERMS] 이미 삭제된 약관 | termsId={}", termsDocumentId);
            return; // 멱등
        }

        // 1. pgvector 벡터 인덱스 하드 삭제 (chat_message_source가 참조하지 않아 자유롭게 삭제 가능)
        ragService.deleteIndex(termsDocumentId);

        // 2. chat_session 의 약관 링크 해제 (세션/대화 이력은 보존, 약관 링크만 null)
        List<ChatSession> sessions = chatSessionRepository.findByTermsDocumentId(termsDocumentId);
        for (ChatSession session : sessions) {
            session.updateTermsDocument(null);
            chatSessionRepository.save(session);
        }

        // 3. S3 원본 (파싱 후 이미 삭제됐을 수 있으나 deleteObject는 멱등)
        s3Service.deleteFile(doc.getS3Key());

        // 4. soft delete — terms_chunk/clause/rider는 보존(과거 채팅 근거 JOIN backing)
        doc.softDelete();
        termsDocumentRepository.save(doc);

        log.info("[TERMS] 약관 소프트 삭제 완료 | termsId={} | 벡터 인덱스 삭제, 세션참조 {}건 해제, 청크/조항/특약 보존",
                termsDocumentId, sessions.size());
    }
}
