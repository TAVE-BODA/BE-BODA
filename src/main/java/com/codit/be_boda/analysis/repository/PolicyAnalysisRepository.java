package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyAnalysisRepository extends JpaRepository<PolicyAnalysis, Long> {
    List<PolicyAnalysis> findByUserOrderByCreatedAtDesc(User user);
    List<PolicyAnalysis> findByUserAndAnalysisStatusOrderByCreatedAtDesc(User user, String status);
}
