package com.scribeai.rag.application.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RagIndexQueueService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexQueueService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RagService ragService;

    @Value("${rag.index-queue.enabled:true}")
    private boolean enabled;

    @Value("${rag.index-queue.max-retries:3}")
    private int maxRetries;

    @Value("${rag.index-queue.base-retry-seconds:20}")
    private int baseRetrySeconds;

    @Value("${rag.index-queue.max-jobs-per-tick:2}")
    private int maxJobsPerTick;

    @Value("${rag.index-queue.enqueue-max-attempts:3}")
    private int enqueueMaxAttempts;

    @Value("${rag.index-queue.enqueue-retry-delay-ms:150}")
    private long enqueueRetryDelayMs;

    // 인덱싱 작업 큐 등록
    @Transactional
    public boolean enqueue(Long documentId) {
        if (documentId == null) {
            return false;
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO rag_index_jobs (document_id, status, retry_count, next_retry_at, created_at, updated_at)
                SELECT ?, 'PENDING', 0, now(), now(), now()
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM rag_index_jobs
                    WHERE document_id = ?
                      AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                )
                """,
                documentId,
                documentId
        );
        return inserted > 0;
    }

    // 큐 등록 재시도
    public boolean enqueueWithRetry(Long documentId) {
        int attempts = Math.max(enqueueMaxAttempts, 1);
        for (int i = 1; i <= attempts; i++) {
            try {
                return enqueue(documentId);
            } catch (Exception e) {
                log.warn("RAG enqueue attempt failed. documentId={}, attempt={}/{}", documentId, i, attempts, e);
                if (i < attempts) {
                    sleepBeforeRetry();
                }
            }
        }
        log.error("RAG enqueue failed after retries. documentId={}, attempts={}", documentId, attempts);
        return false;
    }

    // 큐 소비 스케줄러
    @Scheduled(fixedDelayString = "${rag.index-queue.poll-interval-ms:3000}")
    public void consume() {
        if (!enabled) {
            return;
        }

        int limit = Math.max(maxJobsPerTick, 1);
        for (int i = 0; i < limit; i++) {
            Optional<RagIndexJobClaim> claim = claimNext();
            if (claim.isEmpty()) {
                return;
            }
            process(claim.get());
        }
    }

    //인덱싱 실행
    private void process(RagIndexJobClaim claim) {
        try {
            int chunkCount = ragService.indexDocumentTranscript(claim.documentId(), true);
            markDone(claim.id(), chunkCount);
            log.info("RAG index queue DONE. queueId={}, documentId={}, chunkCount={}", claim.id(), claim.documentId(), chunkCount);
        } catch (Exception e) {
            handleFailure(claim, e);
        }
    }

    //큐 작업 에러
    private void handleFailure(RagIndexJobClaim claim, Exception e) {
        String message = trimErrorMessage(e == null ? null : e.getMessage());
        int nextRetryCount = claim.retryCount() + 1;

        if (nextRetryCount > Math.max(maxRetries, 0)) {
            markFail(claim.id(), nextRetryCount, message);
            log.warn("RAG index queue FAIL. queueId={}, documentId={}, retryCount={}, error={}",
                    claim.id(), claim.documentId(), nextRetryCount, message);
            return;
        }

        long delaySeconds = computeBackoffSeconds(nextRetryCount);
        markRetryWait(claim.id(), nextRetryCount, delaySeconds, message);
        log.warn("RAG index queue RETRY_WAIT. queueId={}, documentId={}, retryCount={}, nextDelaySec={}, error={}",
                claim.id(), claim.documentId(), nextRetryCount, delaySeconds, message);
    }

    private long computeBackoffSeconds(int retryCount) {
        long base = Math.max(baseRetrySeconds, 1);
        long multiplier = 1L << Math.min(Math.max(retryCount - 1, 0), 6);
        return Math.min(base * multiplier, 1800L);
    }

    private String trimErrorMessage(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown indexing error";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }

    private void sleepBeforeRetry() {
        long delay = Math.max(enqueueRetryDelayMs, 0L);
        if (delay == 0L) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    //대기중인 인덱싱 작업 변경
    @Transactional
    protected Optional<RagIndexJobClaim> claimNext() {
        List<RagIndexJobClaim> rows = jdbcTemplate.query(
                """
                WITH next_job AS (
                    SELECT id, document_id, retry_count
                    FROM rag_index_jobs
                    WHERE status IN ('PENDING', 'RETRY_WAIT')
                      AND next_retry_at <= now()
                    ORDER BY created_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE rag_index_jobs r
                SET status = 'RUNNING',
                    updated_at = now()
                FROM next_job
                WHERE r.id = next_job.id
                RETURNING r.id, r.document_id, r.retry_count
                """,
                (rs, rowNum) -> toClaim(rs)
        );
        return rows.stream().findFirst();
    }

    private RagIndexJobClaim toClaim(ResultSet rs) throws SQLException {
        return new RagIndexJobClaim(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getInt("retry_count")
        );
    }

    @Transactional
    protected void markDone(Long queueId, int chunkCount) {
        jdbcTemplate.update(
                """
                UPDATE rag_index_jobs
                SET status = 'DONE',
                    chunk_count = ?,
                    error_message = NULL,
                    updated_at = now()
                WHERE id = ?
                """,
                chunkCount,
                queueId
        );
    }

    @Transactional
    protected void markRetryWait(Long queueId, int retryCount, long delaySeconds, String errorMessage) {
        Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);
        jdbcTemplate.update(
                """
                UPDATE rag_index_jobs
                SET status = 'RETRY_WAIT',
                    retry_count = ?,
                    next_retry_at = ?,
                    error_message = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                retryCount,
                Timestamp.from(nextRetryAt),
                errorMessage,
                queueId
        );
    }

    @Transactional
    protected void markFail(Long queueId, int retryCount, String errorMessage) {
        jdbcTemplate.update(
                """
                UPDATE rag_index_jobs
                SET status = 'FAIL',
                    retry_count = ?,
                    error_message = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                retryCount,
                errorMessage,
                queueId
        );
    }

    private record RagIndexJobClaim(
            Long id,
            Long documentId,
            int retryCount
    ) {
    }
}
