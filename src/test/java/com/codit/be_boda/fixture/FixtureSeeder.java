package com.codit.be_boda.fixture;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.analysis.service.PolicyAnalysisService;
import com.codit.be_boda.analysis.service.TermsAnalysisService;
import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.upload.service.PdfExtractService;
import com.codit.be_boda.upload.service.S3Service;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀 테스트용 픽스처를 만들기 위한 1회성 시더.
 *
 * <p>RDS 종료로 기존 데이터가 사라져, 시연에 썼던 증권/약관 PDF를
 * 실제 업로드 파이프라인(추출 → 마스킹 → 분석/파싱)에 그대로 통과시켜 로컬 DB에 다시 만든다.
 * S3 업로드와 RAG 임베딩은 채팅 답변 생성에 쓰이지 않으므로 Mock으로 대체한다.
 *
 * <p>SEED_RAW_DIR 환경변수가 있을 때만 실행되므로 평소 테스트 실행에는 영향이 없다.
 */
@SpringBootTest
@ActiveProfiles("seed")
@EnabledIfEnvironmentVariable(named = "SEED_RAW_DIR", matches = ".+")
class FixtureSeeder {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Set<String> FINISHED = Set.of("DONE", "ERROR");

    @Autowired private UserRepository userRepository;
    @Autowired private PdfExtractService pdfExtractService;
    @Autowired private PolicyAnalysisService policyAnalysisService;
    @Autowired private TermsAnalysisService termsAnalysisService;
    @Autowired private PolicyAnalysisRepository policyAnalysisRepository;
    @Autowired private TermsDocumentRepository termsDocumentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private S3Service s3Service;
    @MockBean private RagService ragService;

    @Test
    void seed() throws Exception {
        Path rawDir = Path.of(System.getenv("SEED_RAW_DIR"));

        User user = userRepository.save(
                User.createKakaoUser(-System.currentTimeMillis(), "fixture-user", null)
        );

        // 1. 증권: 추출 → 마스킹 → LLM 분석(coverage_item 생성)
        PdfExtractService.ExtractResult policyText = extractPolicy(rawDir);
        assertThat(policyText.success())
                .as("증권 PDF 추출 실패: %s", policyText.errorMessage())
                .isTrue();

        PolicyAnalysis analysis = policyAnalysisService.createAndStartAnalysis(
                user, "policy.pdf", "local/policy.pdf",
                policyText.isOcr(), policyText.text(), null
        );

        // 2. 약관: 추출 → 마스킹 → 특약/조항/청크 파싱 (LLM 사용 안 함)
        MockMultipartFile termsFile = loadPdf(rawDir.resolve("terms.pdf"));
        PdfExtractService.ExtractResult termsText = pdfExtractService.extractTerms(termsFile);
        assertThat(termsText.success())
                .as("약관 PDF 추출 실패: %s", termsText.errorMessage())
                .isTrue();

        Map<Integer, String> pageTexts = pdfExtractService.extractTermsByPage(termsFile);
        TermsDocument terms = termsAnalysisService.createAndStartParsing(
                user, "terms.pdf", "local/terms.pdf",
                termsText.text(), pageTexts, null
        );

        // 3. 비동기 분석/파싱 완료 대기
        String policyStatus = waitUntilFinished(() ->
                policyAnalysisRepository.findById(analysis.getId())
                        .map(PolicyAnalysis::getAnalysisStatus)
                        .orElse("MISSING"));

        String termsStatus = waitUntilFinished(() ->
                termsDocumentRepository.findById(terms.getId())
                        .map(TermsDocument::getParsingStatus)
                        .orElse("MISSING"));

        String summary = """
                [SEED RESULT]
                userId=%d
                analysisId=%d status=%s
                termsDocumentId=%d status=%s

                [coverage_item by coverage_type]
                %s
                [terms] riders=%d clauses=%d chunks=%d
                """.formatted(
                user.getId(), analysis.getId(), policyStatus, terms.getId(), termsStatus,
                jdbcTemplate.queryForList(
                        "SELECT coverage_type || ' : ' || COUNT(*) FROM coverage_item"
                                + " WHERE analysis_id = ? GROUP BY coverage_type ORDER BY 1",
                        String.class, analysis.getId()),
                count("terms_rider", terms.getId()),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM terms_clause c JOIN terms_rider r ON c.rider_id = r.rider_id"
                                + " WHERE r.terms_document_id = ?",
                        Integer.class, terms.getId()),
                count("terms_chunk", terms.getId()));

        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "seed-result.txt"), summary);
        System.out.println(summary);

        assertThat(policyStatus).as("증권 분석 상태").isEqualTo("DONE");
        assertThat(termsStatus).as("약관 파싱 상태").isEqualTo("DONE");
    }

    /**
     * 증권 텍스트 추출.
     * 스캔 이미지 증권은 운영에서 클로바 OCR을 쓰지만 로컬에는 키가 없으므로,
     * 미리 OCR한 텍스트(policy.ocr.txt)가 있으면 그것을 쓰고 운영과 같은 마스킹만 적용한다.
     */
    private PdfExtractService.ExtractResult extractPolicy(Path rawDir) throws Exception {
        Path ocrText = rawDir.resolve("policy.ocr.txt");

        if (Files.exists(ocrText)) {
            String masked = ReflectionTestUtils.invokeMethod(
                    pdfExtractService, "mask", Files.readString(ocrText).trim());
            return new PdfExtractService.ExtractResult(true, true, masked, null, null);
        }

        return pdfExtractService.extract(loadPdf(rawDir.resolve("policy.pdf")));
    }

    private int count(String table, Long termsDocumentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE terms_document_id = ?",
                Integer.class, termsDocumentId);
        return n == null ? 0 : n;
    }

    private MockMultipartFile loadPdf(Path path) throws Exception {
        assertThat(path).as("PDF 파일이 없습니다: %s", path).exists();
        return new MockMultipartFile(
                "file",
                path.getFileName().toString(),
                "application/pdf",
                Files.readAllBytes(path)
        );
    }

    private String waitUntilFinished(Supplier<String> statusSupplier) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        String status = statusSupplier.get();

        while (!FINISHED.contains(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            status = statusSupplier.get();
        }
        return status;
    }
}
