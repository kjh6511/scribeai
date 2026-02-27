package com.scribeai.batch.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribeai.batch.api.dto.BatchJobCreateResponse;
import com.scribeai.batch.api.dto.BatchJobDetailResponse;
import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentSourceType;
import com.scribeai.document.domain.DocumentStatus;
import com.scribeai.document.domain.DocumentSummary;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.document.repository.DocumentSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class BatchJobService {

    private final DocumentRepository documentRepository;
    private final DocumentSummaryRepository documentSummaryRepository;
    private final BatchJobProcessor batchJobProcessor;
    private final ObjectMapper objectMapper;

    // 업로드 파일로 배치 작업 생성
    public BatchJobCreateResponse createJob(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "File is required");
        }

        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String contentType = file.getContentType();

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Failed to read uploaded file", e);
        }
        //추출 문서 저장 준비(상태 추적)
        Document document = documentRepository.save(Document.createPending(DocumentSourceType.UPLOAD, fileName, null));
        //음성 추출
        batchJobProcessor.processFile(document.getId(), fileName, fileBytes, contentType);

        return new BatchJobCreateResponse(document.getId(), document.getStatus(), document.getOriginalName());
    }

    // YouTube URL로 배치 작업 생성
    public BatchJobCreateResponse createYouTubeJob(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "YouTube URL is required");
        }

        String trimmed = url.trim();
        Document document = documentRepository.save(Document.createPending(DocumentSourceType.YOUTUBE, "youtube:" + trimmed, trimmed));
        batchJobProcessor.processYouTube(document.getId(), trimmed);

        return new BatchJobCreateResponse(document.getId(), document.getStatus(), document.getOriginalName());
    }

    // 실패 작업 재시도
    @Transactional
    public BatchJobCreateResponse retryJob(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));

        if (document.getStatus() != DocumentStatus.FAIL) {
            throw new ResponseStatusException(BAD_REQUEST, "Retry is allowed only for FAIL status");
        }

        if (document.getSourceType() == DocumentSourceType.YOUTUBE) {
            String sourceUrl = document.getSourceUrl();
            if (sourceUrl == null || sourceUrl.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "YouTube URL is missing for retry");
            }

            document.prepareRetry();
            documentSummaryRepository.deleteByDocumentId(document.getId());
            batchJobProcessor.processYouTube(document.getId(), sourceUrl);
            return new BatchJobCreateResponse(document.getId(), document.getStatus(), document.getOriginalName());
        }

        throw new ResponseStatusException(
                BAD_REQUEST,
                "Retry for uploaded files is not supported yet. Please upload the file again."
        );
    }

    // 배치 작업 단건 조회
    @Transactional(readOnly = true)
    public BatchJobDetailResponse getJob(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        String summaryJson = documentSummaryRepository.findByDocumentId(document.getId())
                .map(DocumentSummary::getSummaryJson)
                .orElse(null);

        return new BatchJobDetailResponse(
                document.getId(),
                document.getStatus(),
                document.getOriginalName(),
                document.getTranscript(),
                parseSummaryJson(summaryJson),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    // summaryJson 문자열을 JSON 객체로 변환
    private JsonNode parseSummaryJson(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(summaryJson);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode().put("raw", summaryJson);
        }
    }
}
