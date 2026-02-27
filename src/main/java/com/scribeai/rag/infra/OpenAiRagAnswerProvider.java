package com.scribeai.rag.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.rag.application.RagAnswerProvider;
import com.scribeai.rag.application.RagConversationTurn;
import com.scribeai.rag.application.RagSearchHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnProperty(prefix = "rag.answer", name = "provider", havingValue = "openai")
public class OpenAiRagAnswerProvider implements RagAnswerProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxOutputTokens;

    public OpenAiRagAnswerProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${rag.answer.openai.base-url}") String baseUrl,
            @Value("${rag.answer.openai.api-key:}") String apiKey,
            @Value("${rag.answer.openai.model:gpt-4o-mini}") String model,
            @Value("${rag.answer.openai.temperature:0.2}") double temperature,
            @Value("${rag.answer.openai.max-output-tokens:700}") int maxOutputTokens
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
    }

    // OpenAI RAG 답변 생성
    @Override
    public String answer(String question, List<RagSearchHit> sources, List<RagConversationTurn> history) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "question is required");
        }
        if (sources == null || sources.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No RAG sources found for answer");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required for openai rag answer provider");
        }

        try {
            String context = buildContext(sources);
            String historyContext = buildHistoryContext(history);
            String systemPrompt = """
                    당신은 영상/강의 원문 기반 질의응답 도우미입니다.
                    아래 원칙을 반드시 지키세요.
                    1) 제공된 컨텍스트(청크) 안의 정보만 사용합니다.
                    2) 추측, 일반 상식 보강, 새로운 사실 추가를 금지합니다.
                    3) 질문에 바로 답하고, 필요한 경우 핵심 근거를 함께 설명합니다.
                    4) 한국어 존댓말로, 읽기 쉬운 문장으로 답합니다.
                    5) '핵심 답변:', '설명:', '근거:' 같은 라벨을 쓰지 않습니다.
                    6) 목록/머리말보다 자연스러운 문단 1~2개로 답하세요.
                    7) 이전 대화와 현재 컨텍스트가 충돌하면 현재 컨텍스트를 우선합니다.
                    """;
            String userPrompt = "질문:\n" + question.trim()
                    + "\n\n이전 대화(있으면 참조):\n" + historyContext
                    + "\n\n컨텍스트:\n" + context;

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("temperature", temperature);
            request.put("max_tokens", Math.max(maxOutputTokens, 200));
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
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI rag answer response is empty");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI rag answer content is empty");
            }

            return content.trim();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to generate rag answer", e);
        }
    }

    private String buildContext(List<RagSearchHit> sources) {
        StringBuilder sb = new StringBuilder();
        for (RagSearchHit source : sources) {
            sb.append("#")
                    .append(source.chunkIndex())
                    .append(" (score=")
                    .append(String.format("%.4f", source.score()))
                    .append(")\n")
                    .append(source.content() == null ? "" : source.content())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private String buildHistoryContext(List<RagConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (RagConversationTurn turn : history) {
            String q = turn.question() == null ? "" : turn.question().trim();
            String a = turn.answer() == null ? "" : turn.answer().trim();
            if (q.isBlank() && a.isBlank()) {
                continue;
            }
            sb.append("Q: ").append(q).append("\n");
            sb.append("A: ").append(a).append("\n\n");
        }
        String value = sb.toString().trim();
        return value.isBlank() ? "(없음)" : value;
    }
}
