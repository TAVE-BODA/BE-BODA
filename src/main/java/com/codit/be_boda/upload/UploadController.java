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
import com.codit.be_boda.upload.dto.UploadResponse;
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
    private final PolicyAnalysisService policyAnalysisService;
    private final TermsAnalysisService termsAnalysisService;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final TermsDocumentRepository termsDocumentRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;

// 증권 업로드
    @Operation(summary = "보험증권 PDF 업로드",
            description = """
                    증권 PDF를 업로드합니다.
                    chatSessionId를 포함하면 분석 완료 후 해당 채팅방에 자동 연결됩니다.
                    chatSessionId가 없으면 분석만 진행합니다 (마이페이지에서 나중에 연결 가능).
                    """)
    @PostMapping("/policy")
    public ResponseEntity<Object> uploadPolicy(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chatSessionId", required = false) Long chatSessionId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null)
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요해요."));

        PdfExtractService.ExtractResult extracted = pdfExtractService.extract(file);
        if (!extracted.success())
            return ResponseEntity.badRequest().body(
                    Map.of("error", extracted.errorMessage(), "code", extracted.errorCode()));

        User user = userRepository.findById(loginUser.id()).orElseThrow();
        String s3Key = s3Service.uploadFile(file, "policy/" + user.getId());

        // chatSessionId 포함 시 분석 완료 후 채팅방에 자동 연결
        PolicyAnalysis analysis = policyAnalysisService.createAndStartAnalysis(
                user, file.getOriginalFilename(), s3Key,
                extracted.isOcr(), extracted.text(), chatSessionId);

        return ResponseEntity.ok(new UploadResponse(
                "ANALYZING", analysis.getId(), "증권 분석을 시작했어요!"));
    }


    //약관 업로드
    @Operation(summary = "보험약관 PDF 업로드",
            description = """
                    약관 PDF를 업로드합니다.
                    chatSessionId를 포함하면 파싱 완료 후 해당 채팅방에 자동 연결됩니다.
                    chatSessionId가 없으면 파싱만 진행합니다 (마이페이지에서 나중에 연결 가능).
                    """)
    @PostMapping("/terms")
    public ResponseEntity<Object> uploadTerms(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chatSessionId", required = false) Long chatSessionId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null)
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요해요."));

        PdfExtractService.ExtractResult extracted = pdfExtractService.extractTerms(file);
        if (!extracted.success())
            return ResponseEntity.badRequest().body(
                    Map.of("error", extracted.errorMessage(), "code", extracted.errorCode()));

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

        // chatSessionId 포함 시 파싱 완료 후 채팅방에 자동 연결
        TermsDocument doc = termsAnalysisService.createAndStartParsing(
                user, file.getOriginalFilename(), s3Key, extracted.text(), pageTexts, chatSessionId);

        return ResponseEntity.ok(new UploadResponse(
                "ANALYZING", doc.getId(),
                "약관을 읽는 중이에요. 시간이 걸리니 나중에 확인해도 돼요 😊"));
    }


    //상태 조회
    @Operation(summary = "증권 분석 상태 조회")
    @GetMapping("/status/{analysisId}")
    public ResponseEntity<Object> policyStatus(
            @PathVariable Long analysisId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null)
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요해요."));

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
        if (loginUser == null)
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요해요."));

        TermsDocument doc = termsDocumentRepository.findById(termsDocumentId).orElse(null);
        if (doc == null || !doc.getUser().getId().equals(loginUser.id()))
            return ResponseEntity.notFound().build();

        return ResponseEntity.ok(new AnalysisStatusResponse(
                null, null, doc.getId(), doc.getParsingStatus(), false));
    }

    private LoginUser getLoginUser(HttpSession session) {
        return (LoginUser) session.getAttribute("loginUser");
    }
}
