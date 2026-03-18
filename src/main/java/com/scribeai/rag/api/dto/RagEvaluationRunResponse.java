package com.scribeai.rag.api.dto;

import java.util.List;

public record RagEvaluationRunResponse(
        Long documentId,
        int totalQuestions,
        int labeledQuestionCount,
        int successCount,
        int failCount,
        long avgLatencyMs,
        long p95LatencyMs,
        double hitRateAtK,
        double mrrAtK,
        double ndcgAtK,
        boolean indexedDuringRun,
        int chunkCountUsedForIndexing,
        int estimatedEmbeddingCalls,
        int estimatedAnswerCalls,
        int estimatedTotalCalls,
        List<RagEvaluationItem> items,
        List<RagEvaluationComparison> comparisons
) {
    public record RagEvaluationComparison(
            String mode,
            int totalQuestions,
            int labeledQuestionCount,
            int successCount,
            int failCount,
            long avgLatencyMs,
            long p95LatencyMs,
            double hitRateAtK,
            double mrrAtK,
            double ndcgAtK,
            boolean indexedDuringRun,
            int chunkCountUsedForIndexing,
            int estimatedEmbeddingCalls,
            int estimatedAnswerCalls,
            int estimatedTotalCalls,
            List<RagEvaluationItem> items
    ) {
    }

    public record RagEvaluationItem(
            int index,
            String question,
            String status,
            long latencyMs,
            String errorMessage,
            List<Integer> expectedChunkIndexes,
            Boolean hitAtK,
            Double reciprocalRank,
            Double ndcgAtK
    ) {
    }
}
