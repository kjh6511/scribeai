package com.scribeai.rag.infra.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.rag.application.port.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "openai")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddingProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${rag.embedding.openai.base-url}") String baseUrl,
            @Value("${rag.embedding.openai.api-key:}") String apiKey,
            @Value("${rag.embedding.openai.model:text-embedding-3-small}") String model
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    // OpenAI 임베딩 생성
    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required for openai embedding provider");
        }

        String input = text == null ? "" : text;
        long startedAt = System.nanoTime();
        log.info("OpenAI embedding request started. model={}, inputChars={}", model, input.length());

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "input", input
            );

            String responseBody = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI embedding response is empty");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode array = root.path("data").path(0).path("embedding");
            if (!array.isArray() || array.isEmpty()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI embedding vector is empty");
            }

            float[] vector = new float[array.size()];
            int i = 0;
            for (JsonNode node : array) {
                vector[i++] = (float) node.asDouble();
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("OpenAI embedding request finished. model={}, inputChars={}, vectorSize={}, elapsedMs={}",
                    model, input.length(), vector.length, elapsedMs);
            return vector;
        } catch (ResponseStatusException e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI embedding request failed. model={}, inputChars={}, elapsedMs={}, message={}",
                    model, input.length(), elapsedMs, e.getReason());
            throw e;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI embedding request failed. model={}, inputChars={}, elapsedMs={}, message={}",
                    model, input.length(), elapsedMs, e.getMessage());
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to create embedding", e);
        }
    }
}
