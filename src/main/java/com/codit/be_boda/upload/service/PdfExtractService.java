package com.codit.be_boda.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

// PDF 텍스트 추출 서비스
//클로바 OCR API 연동.
//POST {CLOVA_OCR_INVOKE_URL}
//Header: X-OCR-SECRET: {시크릿키}
//Body: { version, requestId, timestamp, lang, images: [{format, name, data}] }
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExtractService {

    @Value("${clova.ocr.invoke-url}")
    private String clovaOcrInvokeUrl;

    @Value("${clova.ocr.secret-key}")
    private String clovaOcrSecretKey;

    private final RestClient restClient = RestClient.create();

    private static final long MAX_SIZE = 20 * 1024 * 1024L;
    private static final int MIN_TEXT_LENGTH = 100;
    private static final float OCR_DPI = 150f; // 해상도 (높을수록 정확도↑, 속도↓)

    // 민감정보 마스킹 패턴
    private static final Pattern RESIDENT_ID = Pattern.compile("\\d{6}-[1-4]\\d{6}");
    private static final Pattern PHONE       = Pattern.compile("\\d{3}-\\d{3,4}-\\d{4}");
    private static final Pattern EMAIL       = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CARD        = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{4}");

    public record ExtractResult(
            boolean success,
            boolean isOcr,
            String text,
            String errorCode,
            String errorMessage
    ) {}

    public ExtractResult extract(MultipartFile file) {
        if (file == null || file.isEmpty())
            return fail("NO_FILE", "파일을 선택해주세요.");
        if (file.getSize() > MAX_SIZE)
            return fail("SIZE_EXCEEDED", "20MB 이하 파일만 올릴 수 있어요.");

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf"))
            return fail("NOT_PDF", "PDF 파일만 올릴 수 있어요.");

        try {
            String pdfText = extractWithPdfBox(file);

            if (pdfText != null && pdfText.trim().length() >= MIN_TEXT_LENGTH) {
                log.info("[PDF] 텍스트 PDF 추출 완료 | 길이={}", pdfText.trim().length());
                return new ExtractResult(true, false, mask(pdfText.trim()), null, null);
            }

            log.info("[PDF] 이미지 PDF 감지 → 클로바 OCR 시작");
            String ocrText = extractWithClovaOcrByPage(file);

            if (ocrText == null || ocrText.trim().length() < MIN_TEXT_LENGTH)
                return fail("LOW_QUALITY", "파일 품질이 낮아 분석하기 어려워요. 보험사 앱에서 다시 받아봐요.");

            log.info("[PDF] 클로바 OCR 추출 완료 | 길이={}", ocrText.trim().length());
            return new ExtractResult(true, true, mask(ocrText.trim()), null, null);

        } catch (Exception e) {
            log.error("[PDF] 추출 실패 | {}", e.getMessage(), e);
            return fail("PARSE_ERROR", "파일을 읽을 수 없어요. 다시 시도해주세요.");
        }
    }

    //약관 추출. (text PDF 만 허용.)
    public ExtractResult extractTerms(MultipartFile file) {
        if (file == null || file.isEmpty())
            return fail("NO_FILE", "파일을 선택해주세요.");
        if (file.getSize() > MAX_SIZE)
            return fail("SIZE_EXCEEDED", "20MB 이하 파일만 올릴 수 있어요.");

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf"))
            return fail("NOT_PDF", "PDF 파일만 올릴 수 있어요.");

        try {
            String text = extractWithPdfBox(file);
            if (text == null || text.trim().length() < MIN_TEXT_LENGTH)
                return fail("LOW_QUALITY", "약관은 텍스트 PDF만 지원해요. 보험사 홈페이지에서 받아봐요.");

            log.info("[PDF] 약관 추출 완료 | 길이={}", text.trim().length());
            return new ExtractResult(true, false, mask(text.trim()), null, null);

        } catch (Exception e) {
            log.error("[PDF] 약관 추출 실패 | {}", e.getMessage());
            return fail("PARSE_ERROR", "파일을 읽을 수 없어요. 다시 시도해주세요.");
        }
    }

    // PDFBox추출 시작.
    private String extractWithPdfBox(MultipartFile file) {
        try (var is = file.getInputStream();
             PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {
            String text = new PDFTextStripper().getText(doc);
            log.info("[PDF] PDFBox 추출 결과 길이={}", text == null ? 0 : text.trim().length());
            return text;
        } catch (Exception e) {
            log.warn("[PDF] PDFBox 추출 실패 (이미지 PDF일 수 있음) | {}", e.getMessage());
            return null;
        }
    }

    private String extractWithClovaOcrByPage(MultipartFile file) throws IOException {
        StringBuilder fullText = new StringBuilder();

        try (var is = file.getInputStream();
             PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {

            int totalPages = doc.getNumberOfPages();
            log.info("[OCR] 총 페이지 수={}", totalPages);

            PDFRenderer renderer = new PDFRenderer(doc);

            for (int page = 0; page < totalPages; page++) {
                log.info("[OCR] 페이지 {}/{} 처리 중...", page + 1, totalPages);

                // 페이지를 이미지로 렌더링
                BufferedImage image = renderer.renderImageWithDPI(page, OCR_DPI);

                // 이미지를 JPEG Base64로 변환
                String base64Image = imageToBase64(image, "jpeg");

                // 클로바 OCR API 호출
                String pageText = callClovaOcr(base64Image, "jpeg", page + 1);
                if (pageText != null && !pageText.isBlank()) {
                    fullText.append(pageText).append("\n");
                }
            }
        }

        return fullText.toString().trim();
    }

    private String imageToBase64(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }


    //클로바 단일 이미지 추출
    @SuppressWarnings("unchecked")
    private String callClovaOcr(String base64Image, String format, int pageNum) {
        Map<String, Object> requestBody = Map.of(
                "version", "V2",
                "requestId", UUID.randomUUID().toString(),
                "timestamp", System.currentTimeMillis(),
                "lang", "ko",
                "images", List.of(Map.of(
                        "format", format,
                        "name", "page_" + pageNum,
                        "data", base64Image
                ))
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(clovaOcrInvokeUrl)
                    .header("X-OCR-SECRET", clovaOcrSecretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return null;

            List<Map<String, Object>> images =
                    (List<Map<String, Object>>) response.get("images");
            if (images == null || images.isEmpty()) return null;

            Map<String, Object> firstImage = images.get(0);
            String inferResult = (String) firstImage.get("inferResult");

            if (!"SUCCESS".equals(inferResult)) {
                log.warn("[OCR] 페이지 {} 인식 실패 | inferResult={}", pageNum, inferResult);
                return null;
            }

            List<Map<String, Object>> fields =
                    (List<Map<String, Object>>) firstImage.get("fields");
            if (fields == null || fields.isEmpty()) return null;

            // inferText 조합
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> field : fields) {
                String inferText = (String) field.get("inferText");
                Boolean lineBreak = (Boolean) field.get("lineBreak");
                if (inferText != null) {
                    sb.append(inferText);
                    sb.append(Boolean.TRUE.equals(lineBreak) ? "\n" : " ");
                }
            }

            String pageText = sb.toString().trim();
            log.info("[OCR] 페이지 {} 완료 | 길이={}", pageNum, pageText.length());
            return pageText;

        } catch (Exception e) {
            log.error("[OCR] 페이지 {} 호출 실패 | {}", pageNum, e.getMessage());
            return null;
        }
    }

    // 민감정보 마스킹

    private String mask(String text) {
        text = RESIDENT_ID.matcher(text).replaceAll("******-*******");
        text = PHONE.matcher(text).replaceAll("***-****-****");
        text = EMAIL.matcher(text).replaceAll("***@***.***");
        text = CARD.matcher(text).replaceAll("****-****-****-****");
        return text;
    }

    private ExtractResult fail(String code, String msg) {
        return new ExtractResult(false, false, null, code, msg);
    }
}
