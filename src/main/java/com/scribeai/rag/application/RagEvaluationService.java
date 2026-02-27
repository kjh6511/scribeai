package com.scribeai.rag.application;

import com.scribeai.rag.api.dto.RagEvaluationRunResponse;
import com.scribeai.rag.api.dto.RagAnswerResult;
import com.scribeai.rag.infra.RagChunkStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final RagService ragService;
    private final RagChunkStore ragChunkStore;
    private final RagEvaluationQuestionResolver questionResolver;
    private final RagEvaluationMetricsCalculator metricsCalculator;

    // 평가 실행
    public RagEvaluationRunResponse run(Long documentId, List<String> rawQuestions, Integer topK, Boolean autoIndex) {
        List<String> questions = questionResolver.resolve(rawQuestions);
        int beforeChunkCount = ragChunkStore.countByDocumentId(documentId);

        ragService.clearConversation(documentId);

        List<RagEvaluationRunResponse.RagEvaluationItem> items = new ArrayList<>();
        List<Long> successLatencies = new ArrayList<>();
        int successCount = 0;
        boolean indexedDuringRun = false;

        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i);
            long startedAt = System.nanoTime();
            try {
                RagAnswerResult result = ragService.ask(documentId, question, topK, autoIndex);
                long latencyMs = metricsCalculator.toMillis(System.nanoTime() - startedAt);
                successCount += 1;
                successLatencies.add(latencyMs);
                if (result.indexedNow()) {
                    indexedDuringRun = true;
                }
                items.add(new RagEvaluationRunResponse.RagEvaluationItem(
                        i + 1,
                        question,
                        "SUCCESS",
                        latencyMs,
                        null
                ));
            } catch (Exception e) {
                long latencyMs = metricsCalculator.toMillis(System.nanoTime() - startedAt);
                items.add(new RagEvaluationRunResponse.RagEvaluationItem(
                        i + 1,
                        question,
                        "FAIL",
                        latencyMs,
                        simplifyErrorMessage(e)
                ));
            }
        }

        ragService.clearConversation(documentId);

        int totalQuestions = questions.size();
        int failCount = totalQuestions - successCount;
        long avgLatencyMs = metricsCalculator.average(successLatencies);
        long p95LatencyMs = metricsCalculator.p95(successLatencies);

        int afterChunkCount = ragChunkStore.countByDocumentId(documentId);
        int chunkCountUsedForIndexing = beforeChunkCount == 0 && afterChunkCount > 0 ? afterChunkCount : 0;
        int estimatedEmbeddingCalls = totalQuestions + chunkCountUsedForIndexing;
        int estimatedAnswerCalls = successCount;
        int estimatedTotalCalls = estimatedEmbeddingCalls + estimatedAnswerCalls;

        return new RagEvaluationRunResponse(
                documentId,
                totalQuestions,
                successCount,
                failCount,
                avgLatencyMs,
                p95LatencyMs,
                indexedDuringRun,
                chunkCountUsedForIndexing,
                estimatedEmbeddingCalls,
                estimatedAnswerCalls,
                estimatedTotalCalls,
                items
        );
    }

    private String simplifyErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "Unknown error";
        }
        String normalized = e.getMessage().replaceAll("\\s+", " ").trim();
        return normalized.length() > 220 ? normalized.substring(0, 220) + "..." : normalized;
    }
}
