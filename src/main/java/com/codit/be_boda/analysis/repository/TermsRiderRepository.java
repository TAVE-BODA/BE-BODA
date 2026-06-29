package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.TermsRider;
import com.codit.be_boda.analysis.domain.TermsDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermsRiderRepository extends JpaRepository<TermsRider, Long> {
    List<TermsRider> findByTermsDocumentOrderByStartPage(TermsDocument termsDocument);
}
