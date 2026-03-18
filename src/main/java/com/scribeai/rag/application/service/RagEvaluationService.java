package com.scribeai.rag.application.service;

import com.scribeai.rag.api.dto.RagAnswerResult;
import com.scribeai.rag.api.dto.RagEvaluationQuestionSpec;
import com.scribeai.rag.api.dto.RagEvaluationRunResponse;
import com.scribeai.rag.application.model.RagEvaluationQuestion;
import com.scribeai.rag.application.model.RagPipelineMode;
import com.scribeai.rag.application.model.RagSearchHit;
import com.scribeai.rag.infra.store.RagChunkStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final RagService ragService;
    private final RagChunkStore ragChunkStore;
    private final RagEvaluationQuestionResolver questionResolver;
    private final RagEvaluationMetricsCalculator metricsCalculator;

    // 평가 실행 (모드 단일/비교)
    public RagEvaluationRunResponse run(
            Long documentId,
            List<String> rawQuestions,
            List<RagEvaluationQuestionSpec> questionSet,
            List<String> compareModes,
            Integer topK,
            Boolean autoIndex
    ) {
        List<RagEvaluationQuestion> questions = questionResolver.resolve(rawQuestions, questionSet);
        List<RagPipelineMode> modes = resolveModes(compareModes);
        Map<String, List<Integer>> autoExpectedByQuestion = buildAutoExpectedChunkMap(documentId, questions, topK, autoIndex);

        List<RagEvaluationRunResponse.RagEvaluationComparison> comparisons = new ArrayList<>();
        for (RagPipelineMode mode : modes) {
            comparisons.add(evaluateMode(documentId, questions, autoExpectedByQuestion, topK, autoIndex, mode));
        }

        RagEvaluationRunResponse.RagEvaluationComparison primary = selectPrimary(comparisons);
        return new RagEvaluationRunResponse(
                documentId,
                primary.totalQuestions(),
                primary.labeledQuestionCount(),
                primary.successCount(),
                primary.failCount(),
                primary.avgLatencyMs(),
                primary.p95LatencyMs(),
                primary.hitRateAtK(),
                primary.mrrAtK(),
                primary.ndcgAtK(),
                primary.indexedDuringRun(),
                primary.chunkCountUsedForIndexing(),
                primary.estimatedEmbeddingCalls(),
                primary.estimatedAnswerCalls(),
                primary.estimatedTotalCalls(),
                primary.items(),
                comparisons
        );
    }

    private RagEvaluationRunResponse.RagEvaluationComparison evaluateMode(
            Long documentId,
            List<RagEvaluationQuestion> questions,
            Map<String, List<Integer>> autoExpectedByQuestion,
            Integer topK,
            Boolean autoIndex,
            RagPipelineMode mode
    ) {
        // 인덱싱 전 청크 개수
        int beforeChunkCount = ragChunkStore.countByDocumentId(documentId);

        ragService.clearConversation(documentId);

        List<RagEvaluationRunResponse.RagEvaluationItem> items = new ArrayList<>();
        List<Long> successLatencies = new ArrayList<>();
        int successCount = 0;
        int labeledQuestionCount = 0;
        int hitCountAtK = 0;
        double mrrSum = 0.0d;
        double ndcgSum = 0.0d;
        boolean indexedDuringRun = false;

        for (int i = 0; i < questions.size(); i++) {
            RagEvaluationQuestion questionItem = questions.get(i);
            String question = questionItem.question();
            List<Integer> expectedChunkIndexes = normalizeExpected(
                    questionItem.expectedChunkIndexes(),
                    autoExpectedByQuestion.get(question)
            );
            long startedAt = System.nanoTime();
            try {
                RagAnswerResult result = ragService.ask(documentId, question, topK, autoIndex, mode);
                long latencyMs = metricsCalculator.toMillis(System.nanoTime() - startedAt);
                successCount += 1;
                successLatencies.add(latencyMs);
                if (result.indexedNow()) {
                    indexedDuringRun = true;
                }

                Boolean hitAtK = null;
                Double reciprocalRank = null;
                Double ndcgAtK = null;
                if (expectedChunkIndexes != null && !expectedChunkIndexes.isEmpty()) {
                    labeledQuestionCount += 1;
                    hitAtK = hasHitAtK(result.sources(), expectedChunkIndexes);
                    reciprocalRank = reciprocalRank(result.sources(), expectedChunkIndexes);
                    ndcgAtK = ndcgAtK(result.sources(), expectedChunkIndexes);

                    if (Boolean.TRUE.equals(hitAtK)) {
                        hitCountAtK += 1;
                    }
                    mrrSum += reciprocalRank;
                    ndcgSum += ndcgAtK;
                }

                items.add(new RagEvaluationRunResponse.RagEvaluationItem(
                        i + 1,
                        question,
                        "SUCCESS",
                        latencyMs,
                        null,
                        expectedChunkIndexes,
                        hitAtK,
                        reciprocalRank,
                        ndcgAtK
                ));
            } catch (Exception e) {
                long latencyMs = metricsCalculator.toMillis(System.nanoTime() - startedAt);
                items.add(new RagEvaluationRunResponse.RagEvaluationItem(
                        i + 1,
                        question,
                        "FAIL",
                        latencyMs,
                        simplifyErrorMessage(e),
                        expectedChunkIndexes,
                        null,
                        null,
                        null
                ));
            }
        }

        ragService.clearConversation(documentId);

        int totalQuestions = questions.size();
        int failCount = totalQuestions - successCount;
        long avgLatencyMs = metricsCalculator.average(successLatencies);
        long p95LatencyMs = metricsCalculator.p95(successLatencies);
        double hitRateAtK = labeledQuestionCount == 0 ? 0.0d : (double) hitCountAtK / labeledQuestionCount;
        double mrrAtK = labeledQuestionCount == 0 ? 0.0d : mrrSum / labeledQuestionCount;
        double ndcgAtK = labeledQuestionCount == 0 ? 0.0d : ndcgSum / labeledQuestionCount;

        int afterChunkCount = ragChunkStore.countByDocumentId(documentId);
        int chunkCountUsedForIndexing = beforeChunkCount == 0 && afterChunkCount > 0 ? afterChunkCount : 0;
        int estimatedEmbeddingCalls = totalQuestions + chunkCountUsedForIndexing;
        int estimatedAnswerCalls = successCount;
        int estimatedTotalCalls = estimatedEmbeddingCalls + estimatedAnswerCalls;

        return new RagEvaluationRunResponse.RagEvaluationComparison(
                mode.name(),
                totalQuestions,
                labeledQuestionCount,
                successCount,
                failCount,
                avgLatencyMs,
                p95LatencyMs,
                hitRateAtK,
                mrrAtK,
                ndcgAtK,
                indexedDuringRun,
                chunkCountUsedForIndexing,
                estimatedEmbeddingCalls,
                estimatedAnswerCalls,
                estimatedTotalCalls,
                items
        );
    }

    private Map<String, List<Integer>> buildAutoExpectedChunkMap(
            Long documentId,
            List<RagEvaluationQuestion> questions,
            Integer topK,
            Boolean autoIndex
    ) {
        Map<String, List<Integer>> expectedByQuestion = new HashMap<>();
        if (questions == null || questions.isEmpty()) {
            return expectedByQuestion;
        }

        int existingChunkCount = ragChunkStore.countByDocumentId(documentId);
        boolean enableAutoIndex = autoIndex == null || autoIndex;
        if (existingChunkCount == 0 && enableAutoIndex) {
            ragService.indexDocumentTranscript(documentId);
        }
        if (ragChunkStore.countByDocumentId(documentId) == 0) {
            return expectedByQuestion;
        }

        int finalTopK = topK == null ? 7 : Math.min(Math.max(topK, 1), 20);
        int expectedTop = Math.min(Math.max(finalTopK, 1), 3);

        for (RagEvaluationQuestion question : questions) {
            if (question == null || question.question() == null || question.question().isBlank()) {
                continue;
            }
            if (question.expectedChunkIndexes() != null && !question.expectedChunkIndexes().isEmpty()) {
                continue;
            }
            List<RagSearchHit> baselineHits = ragService.search(
                    documentId,
                    question.question(),
                    finalTopK,
                    RagPipelineMode.HYBRID_RERANK
            );
            List<Integer> expected = baselineHits.stream()
                    .map(RagSearchHit::chunkIndex)
                    .distinct()
                    .limit(expectedTop)
                    .toList();
            if (!expected.isEmpty()) {
                expectedByQuestion.put(question.question(), expected);
            }
        }
        return expectedByQuestion;
    }

    private List<Integer> normalizeExpected(List<Integer> explicitExpected, List<Integer> autoExpected) {
        if (explicitExpected != null && !explicitExpected.isEmpty()) {
            return explicitExpected;
        }
        return autoExpected == null ? List.of() : autoExpected;
    }

    private List<RagPipelineMode> resolveModes(List<String> compareModes) {
        if (compareModes == null || compareModes.isEmpty()) {
            return List.of(RagPipelineMode.HYBRID_RERANK);
        }

        Set<RagPipelineMode> set = new LinkedHashSet<>();
        for (String mode : compareModes) {
            set.add(RagPipelineMode.from(mode));
        }
        if (set.isEmpty()) {
            return List.of(RagPipelineMode.HYBRID_RERANK);
        }
        return new ArrayList<>(set);
    }

    private RagEvaluationRunResponse.RagEvaluationComparison selectPrimary(List<RagEvaluationRunResponse.RagEvaluationComparison> comparisons) {
        for (RagEvaluationRunResponse.RagEvaluationComparison comparison : comparisons) {
            if ("HYBRID_RERANK".equals(comparison.mode())) {
                return comparison;
            }
        }
        return comparisons.get(0);
    }

    private boolean hasHitAtK(List<RagSearchHit> sources, List<Integer> expectedChunkIndexes) {
        Set<Integer> expected = expectedChunkIndexes.stream().collect(Collectors.toSet());
        return sources.stream().anyMatch(source -> expected.contains(source.chunkIndex()));
    }

    private double reciprocalRank(List<RagSearchHit> sources, List<Integer> expectedChunkIndexes) {
        Set<Integer> expected = expectedChunkIndexes.stream().collect(Collectors.toSet());
        for (int i = 0; i < sources.size(); i++) {
            if (expected.contains(sources.get(i).chunkIndex())) {
                return 1.0d / (i + 1);
            }
        }
        return 0.0d;
    }

    private double ndcgAtK(List<RagSearchHit> sources, List<Integer> expectedChunkIndexes) {
        Set<Integer> expected = expectedChunkIndexes.stream().collect(Collectors.toSet());
        if (expected.isEmpty() || sources.isEmpty()) {
            return 0.0d;
        }

        double dcg = 0.0d;
        for (int i = 0; i < sources.size(); i++) {
            if (expected.contains(sources.get(i).chunkIndex())) {
                dcg += 1.0d / log2(i + 2);
            }
        }

        int idealHits = Math.min(expected.size(), sources.size());
        double idcg = 0.0d;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0d / log2(i + 2);
        }
        if (idcg == 0.0d) {
            return 0.0d;
        }
        return dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private String simplifyErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "Unknown error";
        }
        String normalized = e.getMessage().replaceAll("\\s+", " ").trim();
        return normalized.length() > 220 ? normalized.substring(0, 220) + "..." : normalized;
    }
}
