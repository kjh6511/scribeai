package com.scribeai.summarize.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.common.text.TitleSanitizer;
import com.scribeai.summarize.application.SummarizeProvider;
import com.scribeai.summarize.application.SummaryResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "openai")
public class OpenAiSummarizeProvider implements SummarizeProvider {
    private static final String FIXED_PACK_TYPE = "STANDARD";
    private static final Logger log = LoggerFactory.getLogger(OpenAiSummarizeProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TitleSanitizer titleSanitizer;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final String outputLanguage;
    private final String defaultMode;
    private final int maxTranscriptChars;

    public OpenAiSummarizeProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            TitleSanitizer titleSanitizer,
            @Value("${summarize.openai.base-url}") String baseUrl,
            @Value("${summarize.openai.api-key:}") String apiKey,
            @Value("${summarize.openai.model:gpt-4o-mini}") String model,
            @Value("${summarize.openai.temperature:0.2}") double temperature,
            @Value("${summarize.output-language:ko}") String outputLanguage,
            @Value("${summarize.default-mode:AUTO}") String defaultMode,
            @Value("${summarize.max-transcript-chars:24000}") int maxTranscriptChars
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.titleSanitizer = titleSanitizer;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.outputLanguage = outputLanguage;
        this.defaultMode = defaultMode;
        this.maxTranscriptChars = maxTranscriptChars;
    }

    // OpenAI 요약 생성
    @Override
    public SummaryResult summarize(String transcript, String titleHint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required for openai summarize provider");
        }

        String clampedTranscript = clampTranscript(transcript);
        String sanitizedTitle = titleSanitizer.sanitize(titleHint, 120);
        long startedAt = System.nanoTime();
        log.info("OpenAI summarize request started. model={}, transcriptChars={}, titleHintChars={}",
                model, clampedTranscript.length(), sanitizedTitle.length());

        try {
            //프롬프트 + 추출값 요청
            Map<String, Object> request = buildRequest(clampedTranscript, sanitizedTitle);
            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI summarize response is empty");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI summarize content is empty");
            }
            //결과값 정리
            JsonNode summaryJson = objectMapper.readTree(content);
            List<String> keywords = toStringList(summaryJson.path("keywords"));
            String finalSummary = normalizeFinalSummary(
                    text(summaryJson, "finalSummary", ""),
                    keywords
            );
            List<String> suggestedQuestions = normalizeSuggestedQuestions(
                    toStringList(summaryJson.path("suggestedQuestions")),
                    keywords
            );
            return new SummaryResult(
                    text(summaryJson, "mode", defaultMode),
                    FIXED_PACK_TYPE,
                    text(summaryJson, "title", "요약 결과"),
                    text(summaryJson, "overview", ""),
                    toSections(summaryJson.path("sections")),
                    finalSummary,
                    keywords,
                    suggestedQuestions,
                    normalizeAiComment(text(summaryJson, "aiComment", ""))
            );
        } catch (RestClientResponseException e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            String responseBody = e.getResponseBodyAsString();
            String shortBody = responseBody == null ? "" : responseBody.replaceAll("\\s+", " ").trim();
            if (shortBody.length() > 500) {
                shortBody = shortBody.substring(0, 500) + "...";
            }
            log.warn("OpenAI summarize request failed. model={}, elapsedMs={}, status={}, responseBody={}",
                    model, elapsedMs, e.getRawStatusCode(), shortBody);
            String message = "OpenAI summarize failed (" + e.getRawStatusCode() + "): " + shortBody;
            throw new ResponseStatusException(e.getStatusCode(), message, e);
        } catch (ResponseStatusException e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI summarize request failed. model={}, elapsedMs={}, message={}",
                    model, elapsedMs, e.getReason());
            throw e;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI summarize request failed. model={}, elapsedMs={}, message={}",
                    model, elapsedMs, e.getMessage());
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to summarize with OpenAI", e);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("OpenAI summarize request finished. model={}, elapsedMs={}", model, elapsedMs);
        }
    }
    //프롬프트 요청
    private Map<String, Object> buildRequest(String transcript, String titleHint) {
        String systemPrompt = "당신은 강의/영상 정리 도우미입니다. 입력 전사를 분석해 mode를 LECTURE, MEETING, GENERAL, MULTI_TOPIC 중 하나로 판단하세요. "
                + "MULTI_TOPIC은 서로 독립적인 주제가 3개 이상 전환되며 하나의 중심 주제로 깊게 전개되지 않는 영상입니다. "
                + "입력 전사는 하나의 주제일 수도 있고 여러 주제가 섞인 영상일 수도 있습니다. "
                + "초반에 길게 등장한 주제 하나에만 치우치지 말고 전사 전체를 기준으로 요약하세요. "
                + "반드시 유효한 JSON만 반환하세요. JSON 외 설명, 코드블록, 마크다운을 출력하지 마세요. "
                + "전사에 포함된 [전사 일부 생략 - ...] 표시는 메타 구간 표시이므로 내용으로 요약하지 말고, 전사 일부가 생략되었음을 나타내는 표시로만 해석하세요. "

                + "키는 mode(string), packType(string), title(string), overview(string), sections(array of {heading,notes}), finalSummary(string), keywords(string[]), suggestedQuestions(string[]), aiComment(string) 입니다. "
                + "packType은 항상 '" + FIXED_PACK_TYPE + "'만 사용하세요. "
                + "우선순위는 다음 순서를 반드시 지키세요: 사실성(전사 근거) > JSON 유효성 > 출력 언어 > 필드 형식 > 문체/길이. "

                + "모든 텍스트 필드는 " + outputLanguage + "로 작성하세요. "
                + "문체는 부드러운 존댓말로 쓰고, 일반인/학생이 이해하기 쉽게 명확하게 작성하세요. "
                + "전문 용어가 필요하면 괄호로 아주 짧게 풀어 설명하세요. "

                + "요약/정리는 반드시 전사 근거 기반으로만 작성하세요. "
                + "전사에 없는 사실, 이름, 주장, 숫자, 해석을 추가하지 마세요. "
                + "불명확하거나 잡음이 심한 구간은 추측하지 말고 생략하세요. "
                + "영상 제목이 함께 제공되면 제목은 참고용 보조 신호로만 사용하고, 최종 요약 근거는 전사(STT)를 우선하세요. "
                + "제목과 전사가 충돌하면 반드시 전사를 우선하세요. "
                + "근거 부족 시 문장 수를 줄여도 되며, 추측으로 분량을 채우지 마세요. "

                + "요약은 추상적인 일반론만으로 작성하지 말고, 전사에서 실제로 등장한 구체적인 주제, 사례, 항목, 표현을 반영하세요. "
                + "전사의 핵심을 설명할 때 의미를 유지하되, 전사에 없는 미화/일반화 표현으로 확장하지 마세요. "
                + "전사 후반부에 새로운 핵심 주제, 실전 적용 내용, 사례, 기술, 원칙이 등장하면 앞부분 요약에 묻히지 않도록 sections 또는 finalSummary에 반영하세요. "
                + "전체 요약 시 초반 핵심 개념뿐 아니라 중반/후반의 주요 사례·기술·원칙도 균형 있게 포함하세요. "
                + "전사에 여러 하위 주제, 기능, 사례, 일화, 적용 내용이 등장하면 앞부분에 치우치지 말고 대표 내용을 균형 있게 포함하세요. "
                + "하나의 중심 주제를 다루는 영상이라도 정의/개념 설명뿐 아니라 전사에서 제시된 핵심 논쟁점, 실천 방법, 적용 원칙이 있으면 sections 또는 finalSummary에 반영하세요. "
                + "전사에 핵심 주제를 설명하는 대표 예시/비유/사례가 반복적으로 언급되면, sections 또는 finalSummary에 최소 1개 이상 포함하세요. "
                + "전사에 '어떻게 할 것인가'에 해당하는 방법/절차/학습법/실행 팁이 등장하면 누락하지 말고 최소 1개 이상 포함하세요. "

                + "대화형 전사에서 질문과 답변, 서로 다른 관점, 입장 차이, 반응이 핵심이면 이를 단일 주장으로 뭉개지 말고 주요 논점 흐름으로 정리하세요. "
                + "여러 화자가 등장하는 경우, 화자별 발화를 그대로 나열하기보다 공통 주제와 핵심 논점을 중심으로 통합하되 중요한 관점 차이는 반영하세요. "

                + "sections.notes를 먼저 작성하고, 마지막에 finalSummary를 작성하세요. "
                + "sections.notes는 단순 감상이 아니라 학습용 정리문이어야 하며, 각 섹션마다 전사 근거(예시/방법/비교/사실)를 포함하세요. "
                + "하나의 큰 주제 아래에 뚜렷한 하위 주제 전환(예: 개념 설명 후 실전 기술/사례/적용)이 있으면 하위 주제를 별도 섹션으로 분리할 수 있습니다. "
                + "전사가 여러 개의 팁/기능/항목/설정/방법을 순차적으로 소개하는 구성이라면, 개념 중심 요약으로 축소하지 말고 대표 항목들을 폭넓게 반영하세요. "
                + "리스트형 구성에서는 각 항목 설명을 길게 쓰기보다 항목 수를 더 넓게 커버하는 방향을 우선하세요. "

                + "mode가 LECTURE 또는 GENERAL 또는 MEETING일 때, 전사에 뚜렷한 하위 주제 전환이 있으면 sections를 4-6개 범위에서 구성하세요. "
                + "mode가 MULTI_TOPIC이 아니면 sections.notes는 정보가 충분한 경우 4-7문장, 짧거나 근거가 부족한 경우 3-6문장으로 작성하세요. "
                + "mode가 MULTI_TOPIC이면 sections는 서로 다른 핵심 주제를 대표하도록 구성하고, 같은 주제를 여러 섹션으로 반복하지 마세요. "
                + "mode가 MULTI_TOPIC이면 각 섹션은 커버리지 우선으로 2-4문장(정보 충분 시 3-5문장)으로 간결하게 작성하세요. "

                + "overview는 1-2문장으로 짧게 작성하세요. "
                + "mode가 MULTI_TOPIC이면 title과 overview는 특정 한 주제에 치우치지 말고 영상 전체 범위를 반영하세요. "

                + "finalSummary는 4-7문장 줄글로 작성하고, 불릿/번호/마크다운 기호를 사용하지 마세요. "
                + "finalSummary는 객관적 사실 전달 문체(보고체)로 작성하고, 평가/감상/권유/설득 표현은 사용하지 마세요. "
                + "예: '흥미로웠다', '인상적이다', '중요해 보인다' 같은 판단형 표현 금지. "
                + "finalSummary에는 전사의 구체 포인트를 최소 3개 이상 포함하세요(핵심 개념, 사례, 비교, 숫자, 실험, 고유명사 등). "
                + "mode가 LECTURE, GENERAL, MEETING일 때 finalSummary는 전체 핵심 내용과 흐름을 압축 요약하세요. "
                + "mode가 MULTI_TOPIC일 때 finalSummary는 결론형 단정보다 영상이 다룬 주요 주제 범위를 보여주는 요약으로 작성하고, 특정 한 주제에 과도하게 치우치지 마세요. "

                + "keywords는 6-12개의 간결한 명사/명사구로 작성하세요. "
                + "mode가 MULTI_TOPIC이면 keywords는 한 주제군에 치우치지 않도록 전반의 주제를 반영하세요. "

                + "suggestedQuestions는 영상 내용을 더 깊게 이해하기 위한 질문 3개를 작성하세요. "
                + "suggestedQuestions는 전사에 나온 내용만 바탕으로 작성하고, 추측이나 외삽은 금지합니다. "
                + "suggestedQuestions는 존댓말 의문문으로 작성하고, 각 질문 끝에는 물음표(?)를 포함하세요. "
                + "suggestedQuestions는 과도한 통계형 질문(예: 비율/퍼센트/몇 명/몇 %)을 피하고, 개념/근거/비교/적용 중심으로 작성하세요. "
                + "suggestedQuestions는 너무 일반적인 질문(예: '이게 무엇인가요?')을 피하고, 영상 맥락이 드러나게 작성하세요. "
                + "suggestedQuestions 각 항목은 15-45자 범위를 우선으로 하세요. "

                + "aiComment는 'AI 코멘트' 섹션으로 사용할 문장입니다. "
                + "aiComment는 영상 내용 요약이 아니라, 일반 배경지식/맥락/확장 관점으로 작성하세요. "
                + "aiComment는 전사 외 일반 지식을 허용하되, 전사 내용과 충돌하지 않아야 하며 사실 단정은 보수적으로 하세요. "
                + "aiComment는 2-4문장으로 작성하고, 과장된 단정 표현은 피하세요. "

                + "sections의 각 notes는 빈 문자열을 반환하지 마세요. "
                + "환각(hallucination)은 절대 금지합니다.";

        String userPrompt = "packType=" + FIXED_PACK_TYPE
                + ", modeHint=" + defaultMode
                + "\nTitleHint:\n" + (titleHint == null || titleHint.isBlank() ? "(none)" : titleHint)
                + "\nTranscript:\n" + transcript;

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("temperature", temperature);
        request.put("response_format", buildJsonSchemaResponseFormat());
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return request;
    }
    //JSON 답변 형식
    private Map<String, Object> buildJsonSchemaResponseFormat() {
        Map<String, Object> sectionItemSchema = Map.of(
                "type", "object",
                "required", List.of("heading", "notes"),
                "additionalProperties", false,
                "properties", Map.of(
                        "heading", Map.of("type", "string", "minLength", 2),
                        "notes", Map.of("type", "string", "minLength", 12, "maxLength", 2400)
                )
        );

        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("mode", "packType", "title", "overview", "sections", "finalSummary", "keywords", "suggestedQuestions", "aiComment"),
                "properties", Map.of(
                        "mode", Map.of("type", "string", "enum", List.of("LECTURE", "MEETING", "GENERAL", "MULTI_TOPIC")),
                        "packType", Map.of("type", "string", "enum", List.of(FIXED_PACK_TYPE)),
                        "title", Map.of("type", "string", "minLength", 2),
                        "overview", Map.of("type", "string", "minLength", 8, "maxLength", 220),
                        "sections", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "maxItems", 6,
                                "items", sectionItemSchema
                        ),
                        "finalSummary", Map.of("type", "string", "minLength", 40, "maxLength", 2400),
                        "keywords", Map.of(
                                "type", "array",
                                "minItems", 4,
                                "items", Map.of("type", "string", "minLength", 1)
                        ),
                        "suggestedQuestions", Map.of(
                                "type", "array",
                                "minItems", 3,
                                "maxItems", 3,
                                "items", Map.of("type", "string", "minLength", 10, "maxLength", 80)
                        ),
                        "aiComment", Map.of("type", "string", "minLength", 20, "maxLength", 500)
                )
        );

        return Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "summary_pack",
                        "strict", true,
                        "schema", schema
                )
        );
    }

    private String text(JsonNode node, String field, String defaultValue) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? defaultValue : value;
    }

    private List<SummaryResult.SectionNote> toSections(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }

        List<SummaryResult.SectionNote> sections = new ArrayList<>();
        for (JsonNode section : node) {
            String heading = section.path("heading").asText("").trim();
            String notes = firstNonBlank(
                    section.path("notes").asText(""),
                    section.path("summary").asText(""),
                    section.path("content").asText(""),
                    section.path("detail").asText("")
            );
            if (notes.isBlank()) {
                continue;
            }
            String safeHeading = heading.isBlank() ? "핵심 정리" : heading;
            sections.add(new SummaryResult.SectionNote(safeHeading, notes));
        }
        if (sections.isEmpty()) {
            return List.of(new SummaryResult.SectionNote(
                    "핵심 정리",
                    "전사 품질이 낮아 상세 섹션을 만들기 어려웠습니다. 원문 전사를 함께 확인해 주세요."
            ));
        }
        return sections;
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return "";
    }
    //요약결과 데이터 후처리
    private String normalizeFinalSummary(String rawFinalSummary, List<String> keywords) {
        String normalized = rawFinalSummary == null ? "" : rawFinalSummary.trim();
        if (normalized.isBlank()) {
            return buildFallbackFinalSummary(keywords);
        }

        return normalized
                .replaceAll("(?m)^\\s*[-*]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("\\R{2,}", "\n")
                .trim();
    }
    //요약 결과 값 null 대비
    private String buildFallbackFinalSummary(List<String> keywords) {
        String keywordLine = keywords.isEmpty()
                ? "핵심 키워드를 중심으로 흐름을 다시 확인해 주세요."
                : "핵심 키워드: " + String.join(", ", keywords);
        return "전체 흐름과 주요 근거를 중심으로 핵심 내용을 간결하게 정리했습니다. "
                + "주제별 포인트를 다시 확인하고 실행 또는 학습으로 이어질 항목을 분리해 보는 것을 권장합니다. "
                + keywordLine;
    }
    //코멘트 데이터 후처리
    private String normalizeAiComment(String rawComment) {
        String comment = rawComment == null ? "" : rawComment.trim();
        if (comment.isBlank()) {
            return "영상에서 다룬 핵심 흐름을 먼저 복기한 뒤, 예시와 근거를 함께 정리해 보시면 이해에 도움이 됩니다.";
        }
        return comment
                .replaceAll("(?m)^\\s*[-*]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("\\R{2,}", "\n")
                .trim();
    }
    //질문 데이터 후처리
    private List<String> normalizeSuggestedQuestions(List<String> rawQuestions, List<String> keywords) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawQuestions) {
            if (raw == null) {
                continue;
            }
            String question = raw
                    .replaceAll("^\\s*[-*\\d.)]+\\s*", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (question.isBlank()) {
                continue;
            }
            if (!question.endsWith("?")) {
                question = question + "?";
            }
            if (isStatisticalQuestion(question)) {
                continue;
            }
            if (question.length() < 10 || question.length() > 80) {
                continue;
            }
            if (!normalized.contains(question)) {
                normalized.add(question);
            }
            if (normalized.size() >= 3) {
                break;
            }
        }

        if (normalized.size() < 3) {
            List<String> fallbacks = fallbackSuggestedQuestions(keywords);
            for (String fallback : fallbacks) {
                if (!normalized.contains(fallback)) {
                    normalized.add(fallback);
                }
                if (normalized.size() >= 3) {
                    break;
                }
            }
        }

        return normalized.subList(0, Math.min(3, normalized.size()));
    }

    private boolean isStatisticalQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("비율")
                || q.contains("퍼센트")
                || q.contains("%")
                || q.matches(".*몇\\s*명.*")
                || q.matches(".*몇\\s*%.*");
    }

    private List<String> fallbackSuggestedQuestions(List<String> keywords) {
        String keyword = keywords.isEmpty() ? "핵심 개념" : keywords.get(0);
        return List.of(
                "영상에서 " + keyword + "를 설명할 때 제시한 근거는 무엇인가요?",
                "설명된 방법이나 절차를 실제 상황에 적용하려면 어떤 순서로 보면 좋을까요?",
                "영상에서 비교한 개념들의 차이를 한 문장으로 정리하면 어떻게 말할 수 있나요?"
        );
    }

    //추출값 토큰 길이 제한
    private String clampTranscript(String transcript) {
        if (transcript == null) {
            return "";
        }
        if (maxTranscriptChars <= 0 || transcript.length() <= maxTranscriptChars) {
            return transcript;
        }

        String sep1 = "\n\n[전사 일부 생략 - 중간 구간 발췌]\n\n";
        String sep2 = "\n\n[전사 일부 생략 - 후반 구간 발췌]\n\n";
        int sepLen = sep1.length() + sep2.length();

        // separator까지 포함해서 max를 넘지 않게, 본문 샘플링 예산을 먼저 줄임
        int budget = maxTranscriptChars - sepLen;
        if (budget <= 0) {
            return transcript.substring(0, maxTranscriptChars);
        }

        // 40 / 30 / 30
        int headLen = (int) (budget * 0.40);
        int middleLen = (int) (budget * 0.30);
        int tailLen = budget - headLen - middleLen; // 나머지 30%

        int len = transcript.length();

        // 앞 40%
        String head = transcript.substring(0, Math.min(headLen, len));

        // 중간 30% (정보량 기준 중간 시작점 = budget의 40% 위치)
        int middleStart = Math.min((int) (budget * 0.40), len);
        int middleEnd = Math.min(middleStart + middleLen, len);
        String middle = transcript.substring(middleStart, middleEnd);

        // 뒤 30% (끝에서부터)
        int tailStart = Math.max(0, len - tailLen);
        String tail = transcript.substring(tailStart);

       String result = head + sep1 + middle + sep2 + tail;

        return result.length() > maxTranscriptChars
                ? result.substring(0, maxTranscriptChars)
                : result;
    }

}
