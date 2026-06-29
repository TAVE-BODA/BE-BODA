package com.codit.be_boda.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

import java.io.IOException;
import java.util.regex.Pattern;


//PDF 텍스트 추출 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExtractService {

    private final TextractClient textractClient;

    private static final long MAX_SIZE = 20 * 1024 * 1024L;
    private static final int MIN_TEXT_LENGTH = 100;

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
        // 유효성 검사
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
                // 마스킹 후 반환
                log.info("[PDF] 텍스트 PDF 추출 완료 | 길이={}", pdfText.length());
                return new ExtractResult(true, false, mask(pdfText.trim()), null, null);
            }

            //OCR (AWS Textract) 감지 후 변환..
            log.info("[PDF] 이미지 PDF 감지 → OCR 시작");
            String ocrText = extractWithTextract(file);

            if (ocrText == null || ocrText.trim().length() < MIN_TEXT_LENGTH)
                return fail("LOW_QUALITY", "파일 품질이 낮아 분석하기 어려워요. 보험사 앱에서 다시 받아봐요.");

            log.info("[PDF] OCR 추출 완료 | 길이={}", ocrText.length());
            return new ExtractResult(true, true, mask(ocrText.trim()), null, null);

        } catch (Exception e) {
            log.error("[PDF] 추출 실패 | {}", e.getMessage());
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

            log.info("[PDF] 약관 추출 완료 | 길이={}", text.length());
            return new ExtractResult(true, false, mask(text.trim()), null, null);

        } catch (Exception e) {
            log.error("[PDF] 약관 추출 실패 | {}", e.getMessage());
            return fail("PARSE_ERROR", "파일을 읽을 수 없어요. 다시 시도해주세요.");
        }
    }

    // PDFBox추출 시작.
    private String extractWithPdfBox(MultipartFile file) throws IOException {
        try (var is = file.getInputStream();
             PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            log.warn("[PDF] PDFBox 추출 실패 (이미지 PDF일 수 있음) | {}", e.getMessage());
            return null;
        }
    }


    private String extractWithTextract(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        software.amazon.awssdk.core.SdkBytes sdkBytes =
                software.amazon.awssdk.core.SdkBytes.fromByteArray(bytes);

        DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                .document(Document.builder()
                        .bytes(sdkBytes)
                        .build())
                .build();

        DetectDocumentTextResponse response = textractClient.detectDocumentText(request);

        StringBuilder sb = new StringBuilder();
        for (Block block : response.blocks()) {
            if (block.blockType() == BlockType.LINE && block.text() != null) {
                sb.append(block.text()).append("\n");
            }
        }
        return sb.toString();
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
