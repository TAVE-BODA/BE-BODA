package com.codit.be_boda.upload;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.regex.Pattern;

//PDF 전사
// PDF 텍스트 추출 + 민감정보 마스킹(주민번호, 휴대폰, 이메일, 카드번호) + 유효성 검사
//PDFBox 3.x: PDDocument.load() → Loader.loadPDF(RandomAccessReadBuffer)
@Service
public class PdfExtractService {

    private static final long MAX_SIZE = 20 * 1024 * 1024L;

    // 민감정보 마스킹 패턴
    private static final Pattern RESIDENT_ID = Pattern.compile("\\d{6}-[1-4]\\d{6}");
    private static final Pattern PHONE       = Pattern.compile("\\d{3}-\\d{3,4}-\\d{4}");
    private static final Pattern EMAIL       = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CARD        = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{4}");

    public record ExtractResult(boolean success, String text, String errorCode, String errorMessage) {}

    public ExtractResult extract(MultipartFile file) {
        if (file == null || file.isEmpty())
            return fail("NO_FILE", "파일을 선택해주세요.");
        if (file.getSize() > MAX_SIZE)
            return fail("SIZE_EXCEEDED", "20MB 이하 파일만 올릴 수 있어요.");

        String name = file.getOriginalFilename();
        String type = file.getContentType();
        if (!"application/pdf".equals(type) && (name == null || !name.toLowerCase().endsWith(".pdf")))
            return fail("NOT_PDF", "PDF 파일만 올릴 수 있어요.");

        try (var is = file.getInputStream();
             PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {

            String raw = new PDFTextStripper().getText(doc);

            if (raw == null || raw.trim().length() < 200)
                return fail("LOW_QUALITY", "스캔 품질이 낮아요. 보험사 앱에서 다시 받아 올려봐요.");

            String lower = raw.toLowerCase();
            boolean hasKeyword = lower.contains("보험") || lower.contains("증권")
                    || lower.contains("약관") || lower.contains("피보험자");
            if (!hasKeyword)
                return fail("NOT_INSURANCE", "보험증권 또는 약관 파일을 올려봐요.");

            // 민감정보 마스킹 후 반환
            String masked = mask(raw.trim());
            return new ExtractResult(true, masked, null, null);

        } catch (IOException e) {
            return fail("PARSE_ERROR", "파일을 읽을 수 없어요. 다시 시도해주세요.");
        }
    }

    private String mask(String text) {
        text = RESIDENT_ID.matcher(text).replaceAll("******-*******");
        text = PHONE.matcher(text).replaceAll("***-****-****");
        text = EMAIL.matcher(text).replaceAll("***@***.***");
        text = CARD.matcher(text).replaceAll("****-****-****-****");
        return text;
    }

    private ExtractResult fail(String code, String msg) {
        return new ExtractResult(false, null, code, msg);
    }
}
