package com.codit.be_boda.analysis.domain;

import com.codit.be_boda.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

//약관 문서 Entity
@Entity
@Table(name = "terms_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terms_document_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @Column(name = "terms_title")
    private String termsTitle;

    @Column(name = "masked_text", columnDefinition = "TEXT")
    private String maskedText;

    @Column(name = "parsing_status", nullable = false, length = 30)
    private String parsingStatus = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder
    public TermsDocument(User user, Long analysisId, String originalFileName, String s3Key) {
        this.user = user;
        this.analysisId = analysisId;
        this.originalFileName = originalFileName;
        this.s3Key = s3Key;
        this.parsingStatus = "PENDING";
        this.createdAt = LocalDateTime.now();
    }

    public void startParsing() {
        this.parsingStatus = "ANALYZING";
    }

    public void completeParsing(String companyName, String termsTitle, String maskedText) {
        this.companyName = companyName;
        this.termsTitle = termsTitle;
        this.maskedText = maskedText;
        this.parsingStatus = "DONE";
    }

    public void failParsing(String errorMessage) {
        this.parsingStatus = "ERROR";
        this.errorMessage = errorMessage;
    }

    public void deleteS3Key() {
        this.s3Key = null;
    }
}
