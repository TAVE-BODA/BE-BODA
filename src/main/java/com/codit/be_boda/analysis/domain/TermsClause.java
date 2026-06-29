package com.codit.be_boda.analysis.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


// 조항 단위 Entity
// DEFINITION / PAYMENT_REASON / DETAIL_RULE / EXCLUSION / PERIOD / TABLE
@Entity
@Table(name = "terms_clause")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsClause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "clause_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rider_id", nullable = false)
    private TermsRider termsRider;

    @Column(name = "clause_no", length = 50)
    private String clauseNo;            // 제2-1조, 제2-1조의3 등

    @Column(name = "clause_title")
    private String clauseTitle;

    @Column(name = "clause_type", nullable = false, length = 30)
    private String clauseType;

    // 자기참조 — 하위 조항의 상위 조항
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_clause_id")
    private TermsClause parentClause;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder
    public TermsClause(TermsRider termsRider, String clauseNo, String clauseTitle,
                       String clauseType, TermsClause parentClause) {
        this.termsRider = termsRider;
        this.clauseNo = clauseNo;
        this.clauseTitle = clauseTitle;
        this.clauseType = clauseType;
        this.parentClause = parentClause;
        this.createdAt = LocalDateTime.now();
    }
}
