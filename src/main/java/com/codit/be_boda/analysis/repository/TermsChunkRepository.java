package com.codit.be_boda.analysis.repository;

import com.codit.be_boda.analysis.domain.TermsChunk;
import com.codit.be_boda.analysis.domain.TermsDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermsChunkRepository extends JpaRepository<TermsChunk, Long> {
    List<TermsChunk> findByTermsDocumentOrderByChunkIndex(TermsDocument termsDocument);
    List<TermsChunk> findByTermsRiderIdAndClauseType(Long riderId, String clauseType);
}
