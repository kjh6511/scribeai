package com.scribeai.stt.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.stt.application.SttProvider;
import com.scribeai.stt.application.SttResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnProperty(prefix = "stt", name = "provider", havingValue = "openai")
public class OpenAiSttProvider implements SttProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiSttProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String language;

    public OpenAiSttProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${stt.openai.base-url}") String baseUrl,
            @Value("${stt.openai.api-key:}") String apiKey,
            @Value("${stt.openai.model:whisper-1}") String model,
            @Value("${stt.openai.language:}") String language
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
    }

    // OpenAI STT 변환
    @Override
    public SttResult transcribe(String fileName, byte[] audioBytes, String contentType) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required for openai STT provider");
        }

        String safeFileName = fileName == null || fileName.isBlank() ? "audio.bin" : fileName;
        int audioSize = audioBytes == null ? 0 : audioBytes.length;
        long startedAt = System.nanoTime();
        log.info("OpenAI STT request started. model={}, fileName={}, bytes={}", model, safeFileName, audioSize);
        MediaType mediaType = parseMediaType(contentType);

        MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
        formData.add("model", model);
        if (language != null && !language.isBlank()) {
            formData.add("language", language);
        }

        ByteArrayResource fileResource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return safeFileName;
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(mediaType);
        formData.add("file", new HttpEntity<>(fileResource, partHeaders));

        try {
            String responseBody = restClient.post()
                    .uri("/audio/transcriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI STT response is empty");
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            String transcript = jsonNode.path("text").asText();
            String detectedLanguage = jsonNode.path("language").asText(language == null || language.isBlank() ? "unknown" : language);

            if (transcript == null || transcript.isBlank()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI STT text is empty");
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("OpenAI STT request finished. model={}, fileName={}, bytes={}, elapsedMs={}", model, safeFileName, audioSize, elapsedMs);
            return new SttResult(transcript, detectedLanguage);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.warn("OpenAI STT request failed. model={}, fileName={}, bytes={}, elapsedMs={}, message={}",
                    model, safeFileName, audioSize, elapsedMs, e.getMessage());
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to parse OpenAI STT response", e);
        }
    }
    //미디어 타입 반환
    private MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
