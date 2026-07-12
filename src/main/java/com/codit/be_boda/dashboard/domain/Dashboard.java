package com.codit.be_boda.dashboard.domain;

import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.dashboard.dto.CoverageSummaryDto;
import com.codit.be_boda.user.domain.User;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "dashboard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dashboard {

//  chat_session_id를 PK로 사용
    @Id
    @Column(name = "chat_session_id", nullable = false, length = 255)
    private Long chatSessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "insured_name", nullable = false, length = 100)
    private String insuredName;

    @Column(name = "analysis_completed_at", nullable = false)
    private LocalDate analysisCompletedAt;

    @Type(JsonBinaryType.class)
    @Column(name = "analysis_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> analysisIds = new ArrayList<>();

    @Type(JsonBinaryType.class)
    @Column(name = "company_names", nullable = false, columnDefinition = "jsonb")
    private List<String> companyNames = new ArrayList<>();

    @Type(JsonBinaryType.class)
    @Column(name = "coverage_summaries", nullable = false, columnDefinition = "jsonb")
    private List<CoverageSummaryDto> coverageSummaries = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Dashboard(
            Long chatSessionId,
            User user,
            String insuredName,
            LocalDate analysisCompletedAt,
            List<Long> analysisIds,
            List<String> companyNames,
            List<CoverageSummaryDto> coverageSummaries
    ) {
        this.chatSessionId = chatSessionId;
        this.user = user;
        this.insuredName = insuredName;
        this.analysisCompletedAt = analysisCompletedAt;

        this.analysisIds = analysisIds != null
                ? new ArrayList<>(analysisIds)
                : new ArrayList<>();

        this.companyNames = companyNames != null
                ? new ArrayList<>(companyNames)
                : new ArrayList<>();

        this.coverageSummaries = coverageSummaries != null
                ? new ArrayList<>(coverageSummaries)
                : new ArrayList<>();
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}