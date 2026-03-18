package com.scribeai.rag.infra.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.rag.application.port.RagRerankProvider;
import com.scribeai.rag.application.model.RagSearchHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnProperty(prefix = "rag.rerank", name = "provider", havingValue = "openai")
public class OpenAiRagRerankProvider implements RagRerankProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiRagRerankProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxOutputTokens;
    private final int maxCandidateCharsPerChunk;

    public OpenAiRagRerankProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${rag.rerank.openai.base-url}") String baseUrl,
            @Value("${rag.rerank.openai.api-key:}") String apiKey,
            @Value("${rag.rerank.openai.model:gpt-4o-mini}") String model,
            @Value("${rag.rerank.openai.temperature:0.0}") double temperature,
            @Value("${rag.rerank.openai.max-output-tokens:300}") int maxOutputTokens,
            @Value("${rag.rerank.max-candidate-chars-per-chunk:600}") int maxCandidateCharsPerChunk
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.maxCandidateCharsPerChunk = maxCandidateCharsPerChunk;
    }

    // OpenAI 기반 리랭크
    @Override
    public List<RagSearchHit> rerank(String query, List<RagSearchHit> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int safeTopN = Math.max(topN, 1);
        int queryChars = query == null ? 0 : query.trim().length();
        int candidateCount = candidates.size();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required for openai rag rerank provider");
        }

        long startedAt = System.nanoTime();
        log.info("OpenAI rag-rerank request started. model={}, queryChars={}, candidateCount={}, topN={}",
                model, queryChars, candidateCount, safeTopN);

        try {
            String systemPrompt = """
                    당신은 RAG 검색 후보 재정렬기입니다.
                    입력된 질문과 후보 청크를 보고, 질문과 가장 관련도가 높은 순서대로 chunkIndex를 정렬하세요.
                    출력은 반드시 JSON 객체 1개만 반환하세요.
                    형식: {"orderedChunkIndexes":[3,1,7]}
                    규칙:
                    - 입력에 없는 chunkIndex를 만들지 마세요.
                    - 중복 index를 내지 마세요.
                    - 설명 문장, 코드블록, 마크다운을 출력하지 마세요.
                    """;

            String userPrompt = buildUserPrompt(query, candidates);

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("temperature", temperature);
            request.put("max_tokens", Math.max(maxOutputTokens, 120));
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));

            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return fallbackTopN(candidates, safeTopN);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            List<Integer> orderedIndexes = parseOrderedIndexes(content);
            if (orderedIndexes.isEmpty()) {
                return fallbackTopN(candidates, safeTopN);
            }

            List<RagSearchHit> reranked = mapToOrderedHits(candidates, orderedIndexes, safeTopN);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("OpenAI rag-rerank request finished. model={}, queryChars={}, candidateCount={}, returned={}, elapsedMs={}",
                    model, queryChars, candidateCount, reranked.size(), elapsedMs);
            return reranked;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI rag-rerank request failed. model={}, queryChars={}, candidateCount={}, elapsedMs={}, message={}",
                    model, queryChars, candidateCount, elapsedMs, e.getMessage());
            return fallbackTopN(candidates, safeTopN);
        }
    }

    private String buildUserPrompt(String query, List<RagSearchHit> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("질문:\n")
                .append(query == null ? "" : query.trim())
                .append("\n\n후보 청크:\n");

        int safeMaxChars = Math.max(maxCandidateCharsPerChunk, 200);
        for (RagSearchHit candidate : candidates) {
            String content = candidate.content() == null ? "" : candidate.content().trim();
            if (content.length() > safeMaxChars) {
                content = content.substring(0, safeMaxChars);
            }
            sb.append("- chunkIndex: ")
                    .append(candidate.chunkIndex())
                    .append(", hybridScore: ")
                    .append(String.format("%.6f", candidate.score()))
                    .append(", content: ")
                    .append(content.replaceAll("\\s+", " "))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private List<Integer> parseOrderedIndexes(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String compact = content.trim();
        if (compact.startsWith("```")) {
            compact = compact.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        try {
            JsonNode node = objectMapper.readTree(compact);
            JsonNode array = node.path("orderedChunkIndexes");
            if (!array.isArray() || array.isEmpty()) {
                return List.of();
            }
            List<Integer> result = new ArrayList<>();
            for (JsonNode item : array) {
                if (item.canConvertToInt()) {
                    result.add(item.asInt());
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<RagSearchHit> mapToOrderedHits(List<RagSearchHit> candidates, List<Integer> orderedIndexes, int topN) {
        Map<Integer, RagSearchHit> byChunkIndex = new LinkedHashMap<>();
        for (RagSearchHit candidate : candidates) {
            byChunkIndex.put(candidate.chunkIndex(), candidate);
        }

        List<RagSearchHit> ordered = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (Integer chunkIndex : orderedIndexes) {
            if (chunkIndex == null || used.contains(chunkIndex)) {
                continue;
            }
            RagSearchHit hit = byChunkIndex.get(chunkIndex);
            if (hit != null) {
                ordered.add(hit);
                used.add(chunkIndex);
            }
            if (ordered.size() >= topN) {
                return ordered;
            }
        }

        for (RagSearchHit candidate : candidates) {
            if (!used.contains(candidate.chunkIndex())) {
                ordered.add(candidate);
            }
            if (ordered.size() >= topN) {
                break;
            }
        }
        return ordered;
    }

    private List<RagSearchHit> fallbackTopN(List<RagSearchHit> candidates, int topN) {
        return candidates.size() <= topN ? candidates : candidates.subList(0, topN);
    }
}
