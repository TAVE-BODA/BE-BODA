package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.TermsClause;
import com.codit.be_boda.analysis.domain.TermsRider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermsClauseRepository extends JpaRepository<TermsClause, Long> {
    List<TermsClause> findByTermsRiderAndClauseType(TermsRider termsRider, String clauseType);
    List<TermsClause> findByTermsRider(TermsRider termsRider);
}
