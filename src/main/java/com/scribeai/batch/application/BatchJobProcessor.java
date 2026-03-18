package com.scribeai.batch.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.batch.api.dto.YouTubeExtractedContent;
import com.scribeai.batch.infra.YouTubeSubtitleExtractor;
import com.scribeai.common.text.TranscriptPreprocessor;
import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentSummary;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.document.repository.DocumentSummaryRepository;
import com.scribeai.rag.application.service.RagIndexQueueService;
import com.scribeai.stt.application.SttProvider;
import com.scribeai.stt.application.SttResult;
import com.scribeai.summarize.application.SummarizeProvider;
import com.scribeai.summarize.application.SummaryResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class BatchJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchJobProcessor.class);

    private final DocumentRepository documentRepository;
    private final DocumentSummaryRepository documentSummaryRepository;
    private final YouTubeSubtitleExtractor youTubeSubtitleExtractor;
    private final SttProvider sttProvider;
    private final SummarizeProvider summarizeProvider;
    private final TranscriptPreprocessor transcriptPreprocessor;
    private final RagIndexQueueService ragIndexQueueService;
    private final ObjectMapper objectMapper;

    // 현재 주입된 STT 구현체 확인
    @PostConstruct
    void logProviders() {
        log.info("STT provider loaded: {}", sttProvider.getClass().getSimpleName());
        log.info("Summarize provider loaded: {}", summarizeProvider.getClass().getSimpleName());
    }

    // 파일 배치 비동기 처리
    @Async("batchTaskExecutor")
    @Transactional
    public void processFile(Long documentId, String fileName, byte[] fileBytes, String contentType) {
        documentRepository.findById(documentId).ifPresent(document -> processWithStt(document, fileName, fileBytes, contentType));
    }

    // YouTube 배치 비동기 처리
    @Async("batchTaskExecutor")
    @Transactional
    public void processYouTube(Long documentId, String url) {
        documentRepository.findById(documentId).ifPresent(document -> {
            try {
                document.markRunning();
                //제목+자막 추출
                YouTubeExtractedContent extracted = youTubeSubtitleExtractor.extractContent(url);
                //전처리
                String transcript = transcriptPreprocessor.clean(extracted.transcript());
                //내용 요약 정리
                SummaryResult summaryResult = summarizeProvider.summarize(transcript, extracted.title());
                String summaryJson = objectMapper.writeValueAsString(summaryResult);

                document.markDone(transcript);
                upsertSummary(document.getId(), summaryJson);
                if (!ragIndexQueueService.enqueueWithRetry(document.getId())) {
                    log.warn("RAG enqueue skipped after retries. documentId={}", document.getId());
                }
            } catch (Exception e) {
                log.error("YouTube document processing failed. documentId={}", documentId, e);
                document.markFail(extractErrorMessage(e));
                documentSummaryRepository.deleteByDocumentId(document.getId());
            }
        });
    }

    // STT/요약 실행 후 상태 반영
    private void processWithStt(Document document, String fileName, byte[] fileBytes, String contentType) {
        try {
            document.markRunning();
            //stt 추출
            SttResult sttResult = sttProvider.transcribe(fileName, fileBytes, contentType);
            //전처리
            String transcript = transcriptPreprocessor.clean(sttResult.transcript());
            //요약
            SummaryResult summaryResult = summarizeProvider.summarize(transcript, sanitizeFileTitle(fileName));
            String summaryJson = objectMapper.writeValueAsString(summaryResult);

            document.markDone(transcript);
            upsertSummary(document.getId(), summaryJson);
            if (!ragIndexQueueService.enqueueWithRetry(document.getId())) {
                log.warn("RAG enqueue skipped after retries. documentId={}", document.getId());
            }
        } catch (JsonProcessingException e) {
            log.error("Summary serialization failed. documentId={}", document.getId(), e);
            document.markFail("Summary serialization failed");
        } catch (Exception e) {
            log.error("File document processing failed. documentId={}", document.getId(), e);
            document.markFail(extractErrorMessage(e));
            documentSummaryRepository.deleteByDocumentId(document.getId());
        }
    }

    //요약 결과 저장
    private void upsertSummary(Long documentId, String summaryJson) {
        DocumentSummary summary = documentSummaryRepository.findByDocumentId(documentId)
                .orElseGet(() -> DocumentSummary.create(documentId, summaryJson));
        summary.changeSummaryJson(summaryJson);
        documentSummaryRepository.save(summary);
    }

    // 예외 메시지 추출
    private String extractErrorMessage(Exception e) {
        if (e instanceof ResponseStatusException responseStatusException) {
            String reason = responseStatusException.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
            return responseStatusException.getStatusCode().toString();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Unexpected processing error" : message;
    }
    //파일 제목 처리
    private String sanitizeFileTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String base = fileName.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
        String cleaned = base.replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }
}
