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
import com.codit.be_boda.upload.dto.PolicyUploadBatchResponse;
import com.codit.be_boda.upload.dto.PolicyUploadResultResponse;
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

import java.util.ArrayList;
import java.util.List;
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

    // 증권 업로드 (단일 file / 다중 files 모두 지원)
    @Operation(summary = "보험증권 PDF 업로드",
            description = """
                    증권 PDF를 업로드합니다.
                    - 단일 업로드: file 파라미터 사용 → UploadResponse 반환
                    - 다중 업로드: files 파라미터 사용(반복 전송) → PolicyUploadBatchResponse 반환
                    chatSessionId를 포함하면 분석 완료 후 해당 채팅방에 자동 연결됩니다.
                    한 채팅방에는 증권을 최대 3개까지 연결할 수 있습니다.
                    다중 업로드에서 일부 파일이 실패해도 나머지는 정상 접수됩니다.
                    """)
    @PostMapping("/policy")
    public ResponseEntity<Object> uploadPolicy(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "chatSessionId", required = false) Long chatSessionId,
            HttpSession session) {

        LoginUser loginUser = getLoginUser(session);
        if (loginUser == null) return unauthorized();

        // files(다중)가 오면 그것을 쓰고, 없으면 file(단일)을 사용한다
        boolean isBatch = files != null && !files.isEmpty();
        List<MultipartFile> targets = new ArrayList<>();
        if (isBatch) {
            targets.addAll(files);
        } else if (file != null && !file.isEmpty()) {
            targets.add(file);
        }

        if (targets.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new UploadErrorResponse("NO_FILE", "파일을 선택해주세요."));
        }

        if (chatSessionId == null) {
            // 채팅방 연결 없이 업로드되면 chat_session_policy / 대시보드가 생성되지 않는다
            log.warn("[업로드] chatSessionId 없이 증권 업로드 | 파일수={} | 채팅방 연결/대시보드 생성이 건너뛰어집니다",
                    targets.size());
        }

        User user = userRepository.findById(loginUser.id()).orElseThrow();

        // 이미 채팅방에 연결된 증권 수 (이번 요청에서 접수될 때마다 함께 증가시킨다)
        int linkedCount = (chatSessionId == null)
                ? 0
                : chatSessionPolicyRepository.findByChatSessionId(chatSessionId).size();

        List<PolicyUploadResultResponse> results = new ArrayList<>();
        List<Long> acceptedIds = new ArrayList<>();

        for (MultipartFile target : targets) {
            String fileName = target.getOriginalFilename();

            // 증권 1~3개 상한 (이번 요청 내 누적분까지 포함해서 검사)
            if (chatSessionId != null && linkedCount >= ChatSessionPolicy.MAX_PER_SESSION) {
                results.add(PolicyUploadResultResponse.failed(fileName,
                        "POLICY_LIMIT_EXCEEDED",
                        "한 채팅방에는 증권을 최대 " + ChatSessionPolicy.MAX_PER_SESSION + "개까지 올릴 수 있어요."));
                continue;
            }

            PdfExtractService.ExtractResult extracted = pdfExtractService.extract(target);
            if (!extracted.success()) {
                results.add(PolicyUploadResultResponse.failed(
                        fileName, extracted.errorCode(), extracted.errorMessage()));
                continue;
            }

            // 보험 서류인지 확인
            if (!documentValidator.isPolicyDocument(extracted.text())) {
                results.add(PolicyUploadResultResponse.failed(fileName,
                        "NOT_INSURANCE_DOCUMENT",
                        "보험증권 파일이 아닌 것 같아요. 보험사에서 받은 증권 PDF를 올려주세요."));
                continue;
            }

            String s3Key = s3Service.uploadFile(target, "policy/" + user.getId());

            PolicyAnalysis analysis = policyAnalysisService.createAndStartAnalysis(
                    user, fileName, s3Key,
                    extracted.isOcr(), extracted.text(), chatSessionId);

            acceptedIds.add(analysis.getId());
            results.add(PolicyUploadResultResponse.accepted(fileName, analysis.getId()));

            if (chatSessionId != null) {
                linkedCount++;
            }

            log.info("[업로드] 증권 접수 | analysisId={} | chatSessionId={} | file={}",
                    analysis.getId(), chatSessionId, fileName);
        }

        // 단일 업로드는 기존 응답 형식을 유지 (프론트 호환)
        if (!isBatch) {
            PolicyUploadResultResponse only = results.get(0);
            if ("FAILED".equals(only.status())) {
                return ResponseEntity.badRequest().body(
                        new UploadErrorResponse(only.code(), only.error()));
            }
            return ResponseEntity.ok(new UploadResponse(
                    "ANALYZING", only.analysisId(), "증권 분석을 시작했어요!"));
        }

        int failedCount = results.size() - acceptedIds.size();

        // 전부 실패한 경우에만 400
        if (acceptedIds.isEmpty()) {
            return ResponseEntity.badRequest().body(new PolicyUploadBatchResponse(
                    chatSessionId, targets.size(), 0, failedCount, List.of(), results,
                    "업로드한 파일을 분석할 수 없어요."));
        }

        String message;
        if (chatSessionId == null) {
            // 채팅방에 연결되지 않으면 chat_session_policy / 대시보드가 만들어지지 않는다
            message = "증권은 접수했지만 채팅방에 연결되지 않았어요. "
                    + "chatSessionId를 함께 보내야 채팅방 연결과 대시보드가 생성돼요.";
        } else if (failedCount == 0) {
            message = "증권 분석을 시작했어요!";
        } else {
            message = "일부 파일만 접수했어요. 실패한 파일은 다시 확인해주세요.";
        }

        return ResponseEntity.ok(new PolicyUploadBatchResponse(
                chatSessionId, targets.size(), acceptedIds.size(), failedCount,
                acceptedIds, results, message));
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
