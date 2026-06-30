package com.codit.be_boda.analysis.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

//보장 항목 카드 Entity
@Entity
@Table(name = "coverage_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoverageItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coverage_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false)
    private PolicyAnalysis policyAnalysis;

    // 진단/수술/입원/실손/골절재해/치아
    @Column(name = "coverage_type", nullable = false, length = 50)
    private String coverageType;

    @Column(name = "is_detected", nullable = false)
    private Boolean isDetected = false;

    @Column(name = "exclusion_keywords", columnDefinition = "TEXT")
    private String exclusionKeywords;

    // 약관 연결 후 RAG로 채워짐
    @Column(name = "evidence_text", columnDefinition = "TEXT")
    private String evidenceText;

    // JSONB: 카드 타입별로 세부 필드 다름
    @Type(JsonBinaryType.class)
    @Column(name = "detail", columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder
    public CoverageItem(PolicyAnalysis policyAnalysis, String coverageType,
                        Boolean isDetected, String exclusionKeywords,
                        Map<String, Object> detail) {
        this.policyAnalysis = policyAnalysis;
        this.coverageType = coverageType;
        this.isDetected = isDetected != null ? isDetected : false;
        this.exclusionKeywords = exclusionKeywords;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }

    // 약관 연결 후 근거 원문 업데이트
    public void updateEvidence(String evidenceText) {
        this.evidenceText = evidenceText;
    }
}
