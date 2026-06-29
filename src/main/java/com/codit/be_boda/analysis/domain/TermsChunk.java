package com.codit.be_boda.analysis.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

//약관 청크 Entity — RAG 검색 최소 단위
@Entity
@Table(name = "terms_chunk")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_document_id", nullable = false)
    private TermsDocument termsDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rider_id")
    private TermsRider termsRider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clause_id")
    private TermsClause termsClause;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "clause_type", length = 30)
    private String clauseType;          // 검색 필터용 복사본

    @Column(name = "section_title")
    private String sectionTitle;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder
    public TermsChunk(TermsDocument termsDocument, TermsRider termsRider,
                      TermsClause termsClause, Integer chunkIndex,
                      String clauseType, String sectionTitle, String chunkText) {
        this.termsDocument = termsDocument;
        this.termsRider = termsRider;
        this.termsClause = termsClause;
        this.chunkIndex = chunkIndex;
        this.clauseType = clauseType;
        this.sectionTitle = sectionTitle;
        this.chunkText = chunkText;
        this.createdAt = LocalDateTime.now();
    }
}
