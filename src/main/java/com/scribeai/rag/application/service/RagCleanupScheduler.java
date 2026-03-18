package com.scribeai.rag.application.service;

import com.scribeai.document.domain.DocumentStatus;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.document.repository.DocumentSummaryRepository;
import com.scribeai.rag.infra.store.RagChunkStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagCleanupScheduler.class);

    private final RagChunkStore ragChunkStore;
    private final DocumentRepository documentRepository;
    private final DocumentSummaryRepository documentSummaryRepository;

    @Value("${rag.cleanup.retention-minutes:30}")
    private int retentionMinutes;

    // TTL 지난 RAG 청크 정리
    @Scheduled(
            fixedDelayString = "${rag.cleanup.interval-ms:1200000}",
            initialDelayString = "${rag.cleanup.initial-delay-ms:60000}"
    )
    @Transactional
    public void cleanupExpiredChunks() {
        int safeRetention = Math.max(retentionMinutes, 1);
        int deleted = ragChunkStore.deleteOlderThanMinutes(safeRetention);
        int summaryDeleted = documentSummaryRepository.deleteByDocumentUpdatedBefore(
                LocalDateTime.now().minusMinutes(safeRetention)
        );
        int cleared = documentRepository.clearContentsOlderThan(
                LocalDateTime.now().minusMinutes(safeRetention),
                List.of(DocumentStatus.DONE, DocumentStatus.FAIL)
        );

        if (deleted > 0) {
            log.info("RAG chunk cleanup completed. retentionMinutes={}, deleted={}", retentionMinutes, deleted);
        }
        if (summaryDeleted > 0) {
            log.info("Document summary cleanup completed. retentionMinutes={}, deleted={}", retentionMinutes, summaryDeleted);
        }
        if (cleared > 0) {
            log.info("Document transcript/error cleanup completed. retentionMinutes={}, cleared={}", retentionMinutes, cleared);
        }
    }
}
