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

//특약 단위 Entity
@Entity
@Table(name = "terms_rider")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsRider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rider_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_document_id", nullable = false)
    private TermsDocument termsDocument;

    @Column(name = "rider_name", nullable = false)
    private String riderName;           // 특약명

    @Column(name = "start_page")
    private Integer startPage;

    @Column(name = "end_page")
    private Integer endPage;

    // 특약별 가변 메타데이터
    @Type(JsonBinaryType.class)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder
    public TermsRider(TermsDocument termsDocument, String riderName,
                      Integer startPage, Integer endPage, Map<String, Object> metadata) {
        this.termsDocument = termsDocument;
        this.riderName = riderName;
        this.startPage = startPage;
        this.endPage = endPage;
        this.metadata = metadata;
        this.createdAt = LocalDateTime.now();
    }
}
