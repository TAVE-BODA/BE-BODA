package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoverageItemRepository extends JpaRepository<CoverageItem, Long> {
    List<CoverageItem> findByPolicyAnalysisOrderByCoverageType(PolicyAnalysis policyAnalysis);
}
