package com.codit.be_boda.upload;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.analysis.service.PolicyAnalysisService;
import com.codit.be_boda.analysis.service.TermsAnalysisService;
import com.codit.be_boda.analysis.dto.AnalysisStatusResponse;
import com.codit.be_boda.auth.dto.LoginUser;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.upload.dto.UploadErrorResponse;
import com.codit.be_boda.upload.dto.UploadResponse;
import com.codit.be_boda.upload.service.DocumentValidator;
import com.codit.be_boda.upload.service.PdfExtractService;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final PdfExtractService pdfExtractService;
    private final DocumentValidator documentValidator;
    private final PolicyAnalysisService policyAnalysisService;
    private final TermsAnalysisService termsAnalysisService;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final TermsDocumentRepository termsDocumentRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;

    // 증권 업로드
    @Operation(summary = "보험증권 PDF 업로드",
            description = """
                    증권 PDF를 업로드합니다.
                    chatSessionId를 포함하면 분석 완료 후 해당 채팅방에 자동 연결됩니다.
                    한 채팅방에는 증권을 최대 3개까지 연결할 수 있습니다.
                    """)
    @PostMapping("/policy")
    public ResponseEntity<Object> uploadPolicy(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chatSessionId", required = false) Long chatSessionId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        // 증권 1~3개 상한 검증 (분석 시작 전에 막아야 고아 레코드가 생기지 않음)
        if (chatSessionId != null) {
            int linkedCount =
                    chatSessionPolicyRepository.findByChatSessionId(chatSessionId).size();
            if (linkedCount >= ChatSessionPolicy.MAX_PER_SESSION) {
                return ResponseEntity.badRequest().body(new UploadErrorResponse(
                        "POLICY_LIMIT_EXCEEDED",
                        "한 채팅방에는 증권을 최대 " + ChatSessionPolicy.MAX_PER_SESSION + "개까지 올릴 수 있어요."));
            }
        }

        PdfExtractService.ExtractResult extracted = pdfExtractService.extract(file);
        if (!extracted.success())
            return ResponseEntity.badRequest().body(
                    new UploadErrorResponse(extracted.errorCode(), extracted.errorMessage()));

        // 보험 서류인지 확인
        if (!documentValidator.isPolicyDocument(extracted.text()))
            return ResponseEntity.badRequest().body(new UploadErrorResponse(
                    "NOT_INSURANCE_DOCUMENT",
                    "보험증권 파일이 아닌 것 같아요. 보험사에서 받은 증권 PDF를 올려주세요."));

        User user = userRepository.findById(loginUser.id()).orElseThrow();
        String s3Key = s3Service.uploadFile(file, "policy/" + user.getId());

        PolicyAnalysis analysis = policyAnalysisService.createAndStartAnalysis(
                user, file.getOriginalFilename(), s3Key,
                extracted.isOcr(), extracted.text(), chatSessionId);

        return ResponseEntity.ok(new UploadResponse(
                "ANALYZING", analysis.getId(), "증권 분석을 시작했어요!"));
    }


    // 약관 업로드
    @Operation(summary = "보험약관 PDF 업로드",
            description = """
                    약관 PDF를 업로드합니다.
                    chatSessionId를 포함하면 파싱 완료 후 해당 채팅방에 자동 연결됩니다.
                    채팅방에 연결된 증권과 보험사가 다르면 TERMS_MISMATCH로 거절됩니다.
                    """)
    @PostMapping("/terms")
    public ResponseEntity<Object> uploadTerms(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chatSessionId", required = false) Long chatSessionId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        PdfExtractService.ExtractResult extracted = pdfExtractService.extractTerms(file);
        if (!extracted.success())
            return ResponseEntity.badRequest().body(
                    new UploadErrorResponse(extracted.errorCode(), extracted.errorMessage()));

        // 보험 서류인지 확인
        if (!documentValidator.isTermsDocument(extracted.text()))
            return ResponseEntity.badRequest().body(new UploadErrorResponse(
                    "NOT_INSURANCE_DOCUMENT",
                    "보험약관 파일이 아닌 것 같아요. 보험사 홈페이지에서 받은 약관 PDF를 올려주세요."));

        // 채팅방에 연결된 증권과 같은 보험사인지 확인
        if (!documentValidator.matchesLinkedPolicy(extracted.text(), chatSessionId))
            return ResponseEntity.badRequest().body(new UploadErrorResponse(
                    "TERMS_MISMATCH",
                    "업로드한 증권과 다른 보험사의 약관이에요. 증권과 같은 보험사의 약관을 올려주세요."));

        User user = userRepository.findById(loginUser.id()).orElseThrow();
        String s3Key = s3Service.uploadFile(file, "terms/" + user.getId());

        // 페이지별 텍스트 추출 (page_number 저장용)
        Map<Integer, String> pageTexts;
        try {
            pageTexts = pdfExtractService.extractTermsByPage(file);
        } catch (Exception e) {
            log.warn("[업로드] 페이지별 추출 실패, 전체 텍스트로 대체 | {}", e.getMessage());
            pageTexts = null;
        }

        TermsDocument doc = termsAnalysisService.createAndStartParsing(
                user, file.getOriginalFilename(), s3Key, extracted.text(), pageTexts, chatSessionId);

        return ResponseEntity.ok(new UploadResponse(
                "ANALYZING", doc.getId(),
                "약관을 읽는 중이에요. 시간이 걸리니 나중에 확인해도 돼요 😊"));
    }


    // 상태 조회
    @Operation(summary = "증권 분석 상태 조회")
    @GetMapping("/status/{analysisId}")
    public ResponseEntity<Object> policyStatus(
            @PathVariable Long analysisId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        PolicyAnalysis analysis = policyAnalysisRepository.findById(analysisId).orElse(null);
        if (analysis == null || !analysis.getUser().getId().equals(loginUser.id()))
            return ResponseEntity.notFound().build();

        boolean hasCards = !coverageItemRepository
                .findByPolicyAnalysisOrderByCoverageType(analysis).isEmpty();

        return ResponseEntity.ok(new AnalysisStatusResponse(
                analysis.getId(), analysis.getAnalysisStatus(),
                null, null, hasCards));
    }

    @Operation(summary = "약관 파싱 상태 조회")
    @GetMapping("/terms/status/{termsDocumentId}")
    public ResponseEntity<Object> termsStatus(
            @PathVariable Long termsDocumentId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        TermsDocument doc = termsDocumentRepository.findById(termsDocumentId).orElse(null);
        // 삭제된(soft delete) 약관은 없는 것으로 처리
        if (doc == null || !doc.getUser().getId().equals(loginUser.id()) || doc.isDeleted())
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok(new AnalysisStatusResponse(
                null, null, doc.getId(), doc.getParsingStatus(), false));
    }


    // 증권 삭제
    @Operation(summary = "보험증권 삭제",
            description = "증권 분석 결과와 연결된 보장카드, 채팅방-증권 연결, S3 원본을 함께 삭제합니다.")
    @DeleteMapping("/policy/{analysisId}")
    public ResponseEntity<Object> deletePolicy(
            @PathVariable Long analysisId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        try {
            policyAnalysisService.deletePolicy(analysisId, loginUser.id());
            return ResponseEntity.ok(Map.of("message", "증권을 삭제했어요."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(
                    new UploadErrorResponse("FORBIDDEN", e.getMessage()));
        }
    }


    // 약관 삭제 (soft delete: 문서/특약/조항/청크 보존, 벡터 인덱스·세션 링크·S3만 정리)
    @Operation(summary = "보험약관 삭제",
            description = "약관을 삭제 처리합니다. 과거 채팅 근거 보존을 위해 파싱 결과(특약/조항/청크)는 유지하고, "
                    + "벡터 인덱스·채팅방 연결·S3 원본만 정리합니다.")
    @DeleteMapping("/terms/{termsDocumentId}")
    public ResponseEntity<Object> deleteTerms(
            @PathVariable Long termsDocumentId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        try {
            termsAnalysisService.deleteTerms(termsDocumentId, loginUser.id());
            return ResponseEntity.ok(Map.of("message", "약관을 삭제했어요."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(
                    new UploadErrorResponse("FORBIDDEN", e.getMessage()));
        }
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(401).body(
                new UploadErrorResponse("UNAUTHORIZED", "로그인이 필요해요."));
    }

    private LoginUser getLoginUser(HttpSession session) {
        return (LoginUser) session.getAttribute("loginUser");
    }
}
