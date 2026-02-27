package com.scribeai.rag.api.dto;

import java.util.List;

public record RagEvaluationRunResponse(
        Long documentId,
        int totalQuestions,
        int successCount,
        int failCount,
        long avgLatencyMs,
        long p95LatencyMs,
        boolean indexedDuringRun,
        int chunkCountUsedForIndexing,
        int estimatedEmbeddingCalls,
        int estimatedAnswerCalls,
        int estimatedTotalCalls,
        List<RagEvaluationItem> items
) {
    public record RagEvaluationItem(
            int index,
            String question,
            String status,
            long latencyMs,
            String errorMessage
    ) {
    }
}
