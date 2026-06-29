package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermsDocumentRepository extends JpaRepository<TermsDocument, Long> {
    List<TermsDocument> findByUserOrderByCreatedAtDesc(User user);
}
