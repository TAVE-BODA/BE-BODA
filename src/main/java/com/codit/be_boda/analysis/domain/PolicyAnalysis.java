package com.codit.be_boda.analysis.domain;

import com.codit.be_boda.user.domain.User;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

//보험증권 분석 결과 Entity
@Entity
@Table(name = "policy_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "is_ocr", nullable = false)
    private Boolean isOcr = false;

    @Column(name = "masked_text", columnDefinition = "TEXT")
    private String maskedText;

    // JSONB: 보험사별로 추출 필드 다름..
    @Type(JsonBinaryType.class)
    @Column(name = "extracted_data", columnDefinition = "jsonb")
    private Map<String, Object> extractedData;

    @Column(name = "analysis_status", nullable = false, length = 30)
    private String analysisStatus = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Builder
    public PolicyAnalysis(User user, String originalFileName, String s3Key,
                          Boolean isOcr, String maskedText) {
        this.user = user;
        this.originalFileName = originalFileName;
        this.s3Key = s3Key;
        this.isOcr = isOcr != null ? isOcr : false;
        this.maskedText = maskedText;
        this.analysisStatus = "PENDING";
        this.createdAt = LocalDateTime.now();
    }

    public void startAnalysis() {
        this.analysisStatus = "ANALYZING";
    }

    public void completeAnalysis(Map<String, Object> extractedData) {
        this.extractedData = extractedData;
        this.analysisStatus = "DONE";
        this.completedAt = LocalDateTime.now();
    }

    public void failAnalysis(String errorMessage) {
        this.analysisStatus = "ERROR";
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void deleteS3Key() {
        this.s3Key = null;
    }

    //보험사명, 보험가입일, 보험만기일
    public String getCompanyName() {
        return getExtractedDataValue("companyName");
    }

    public String getInsuranceStartDate() {
        return getExtractedDataValue("insuranceStartDate");
    }

    public String getInsuranceEndDate() {
        return getExtractedDataValue("insuranceEndDate");
    }

    private String getExtractedDataValue(String key) {
        if (this.extractedData == null) {
            return null;
        }

        Object value = this.extractedData.get(key);
        return value != null ? value.toString() : null;
    }
}


