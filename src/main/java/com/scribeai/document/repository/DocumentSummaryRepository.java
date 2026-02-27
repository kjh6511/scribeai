package com.scribeai.document.repository;

import com.scribeai.document.domain.DocumentSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DocumentSummaryRepository extends JpaRepository<DocumentSummary, Long> {

    Optional<DocumentSummary> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM document_summaries ds
            USING documents d
            WHERE ds.document_id = d.id
              AND d.updated_at < :cutoff
              AND d.status IN ('DONE', 'FAIL')
            """, nativeQuery = true)
    int deleteByDocumentUpdatedBefore(@Param("cutoff") LocalDateTime cutoff);
}

