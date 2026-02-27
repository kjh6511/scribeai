package com.scribeai.realtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.common.text.TranscriptPreprocessor;
import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentStatus;
import com.scribeai.document.domain.DocumentSummary;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.document.repository.DocumentSummaryRepository;
import com.scribeai.rag.application.RagIndexQueueService;
import com.scribeai.realtime.api.dto.AudioChunkMessage;
import com.scribeai.realtime.api.dto.CaptionMessage;
import com.scribeai.realtime.api.dto.RealtimeErrorMessage;
import com.scribeai.realtime.api.dto.SummaryPushMessage;
import com.scribeai.stt.application.SttProvider;
import com.scribeai.stt.application.SttResult;
import com.scribeai.summarize.application.SummarizeProvider;
import com.scribeai.summarize.application.SummaryResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class RealtimeTranscriptionService {

    private final SttProvider sttProvider;
    private final SummarizeProvider summarizeProvider;
    private final SimpMessagingTemplate messagingTemplate;
    private final TranscriptPreprocessor transcriptPreprocessor;
    private final DocumentRepository documentRepository;
    private final DocumentSummaryRepository documentSummaryRepository;
    private final RagIndexQueueService ragIndexQueueService;
    private final ObjectMapper objectMapper;

    private final Map<Long, RealtimeState> states = new ConcurrentHashMap<>();

    public RealtimeTranscriptionService(
            SttProvider sttProvider,
            SummarizeProvider summarizeProvider,
            SimpMessagingTemplate messagingTemplate,
            TranscriptPreprocessor transcriptPreprocessor,
            DocumentRepository documentRepository,
            DocumentSummaryRepository documentSummaryRepository,
            RagIndexQueueService ragIndexQueueService,
            ObjectMapper objectMapper
    ) {
        this.sttProvider = sttProvider;
        this.summarizeProvider = summarizeProvider;
        this.messagingTemplate = messagingTemplate;
        this.transcriptPreprocessor = transcriptPreprocessor;
        this.documentRepository = documentRepository;
        this.documentSummaryRepository = documentSummaryRepository;
        this.ragIndexQueueService = ragIndexQueueService;
        this.objectMapper = objectMapper;
    }

    // 오디오 청크 처리
    @Async("realtimeTaskExecutor")
    public void handleChunk(Long documentId, AudioChunkMessage message) {
        RealtimeState state = states.computeIfAbsent(documentId, id -> new RealtimeState());

        try {
            byte[] audioBytes = decodeBase64(message.base64Audio());
            String fileName = safeFileName(message.fileName(), message.sequence());
            String contentType = message.contentType() == null || message.contentType().isBlank()
                    ? "audio/webm"
                    : message.contentType();

            SttResult sttResult = sttProvider.transcribe(fileName, audioBytes, contentType);
            String text = transcriptPreprocessor.clean(sttResult.transcript());
            if (text == null || text.isBlank()) {
                return;
            }

            state.append(text);
            markDocumentRunning(documentId);

            messagingTemplate.convertAndSend(
                    captionTopic(documentId),
                    new CaptionMessage(documentId, message.sequence(), text, sttResult.language())
            );
        } catch (Exception e) {
            messagingTemplate.convertAndSend(
                    errorTopic(documentId),
                    new RealtimeErrorMessage(documentId, safeMessage(e))
            );
        }
    }

    // 실시간 문서 종료 표시
    public void finish(Long documentId) {
        states.computeIfAbsent(documentId, id -> new RealtimeState()).markFinished();
    }

    // 수동 요약 실행
    @Transactional
    public SummaryResult summarizeNow(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));

        RealtimeState state = states.get(documentId);
        if (state == null) {
            throw new ResponseStatusException(NOT_FOUND, "Realtime document not found: " + documentId);
        }

        String transcript = transcriptPreprocessor.clean(state.fullText());
        if (transcript.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "No transcript available for summary");
        }

        SummaryResult summary = summarizeProvider.summarize(transcript, document.getOriginalName());
        saveRealtimeResult(document, transcript, summary);
        messagingTemplate.convertAndSend(summaryTopic(documentId), new SummaryPushMessage(documentId, summary));
        return summary;
    }

    // 문서 텍스트 조회
    public String getAccumulatedTranscript(Long documentId) {
        RealtimeState state = states.get(documentId);
        return state == null ? "" : state.fullText();
    }

    // 문서 상태 초기화
    public void clear(Long documentId) {
        states.remove(documentId);
    }

    private String captionTopic(Long documentId) {
        return "/topic/realtime/" + documentId + "/captions";
    }

    private String summaryTopic(Long documentId) {
        return "/topic/realtime/" + documentId + "/summaries";
    }

    private String errorTopic(Long documentId) {
        return "/topic/realtime/" + documentId + "/errors";
    }

    private byte[] decodeBase64(String base64Audio) {
        if (base64Audio == null || base64Audio.isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(base64Audio.getBytes(StandardCharsets.UTF_8));
    }

    private String safeFileName(String fileName, Long sequence) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        long seq = sequence == null ? 0L : sequence;
        return "chunk-" + seq + ".webm";
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Realtime processing failed" : message;
    }

    private void markDocumentRunning(Long documentId) {
        documentRepository.findById(documentId).ifPresent(document -> {
            if (document.getStatus() == DocumentStatus.PENDING) {
                document.markRunning();
            }
        });
    }

    private void saveRealtimeResult(Document document, String transcript, SummaryResult summary) {
        Long documentId = document.getId();
        document.markDone(transcript);

        try {
            String summaryJson = objectMapper.writeValueAsString(summary);
            DocumentSummary documentSummary = documentSummaryRepository.findByDocumentId(documentId)
                    .orElseGet(() -> DocumentSummary.create(documentId, summaryJson));
            documentSummary.changeSummaryJson(summaryJson);
            documentSummaryRepository.save(documentSummary);
            ragIndexQueueService.enqueue(documentId);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "Failed to persist realtime summary", e);
        }
    }

    private static class RealtimeState {
        private final StringBuilder full = new StringBuilder();
        private volatile boolean finished;
        private String lastNormalizedChunk = "";

        synchronized void append(String text) {
            if (finished) {
                return;
            }
            String normalized = text.replaceAll("\\s+", " ").trim();
            if (normalized.isBlank()) {
                return;
            }
            if (normalized.equals(lastNormalizedChunk)) {
                return;
            }
            if (full.length() > 0) {
                full.append(' ');
            }
            full.append(normalized);
            lastNormalizedChunk = normalized;
        }

        synchronized String fullText() {
            return full.toString();
        }

        void markFinished() {
            this.finished = true;
        }
    }
}
