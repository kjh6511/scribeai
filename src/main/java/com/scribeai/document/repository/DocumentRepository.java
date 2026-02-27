package com.scribeai.document.repository;

import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Document d
            SET d.transcript = null,
                d.errorMessage = null
            WHERE d.updatedAt < :cutoff
              AND d.status IN :statuses
              AND (d.transcript IS NOT NULL OR d.errorMessage IS NOT NULL)
            """)
    int clearContentsOlderThan(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("statuses") Collection<DocumentStatus> statuses
    );
}

