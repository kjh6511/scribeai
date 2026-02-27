package com.scribeai.batch.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.scribeai.document.domain.DocumentStatus;

import java.time.LocalDateTime;

public record BatchJobDetailResponse(
        Long documentId,
        DocumentStatus status,
        String originalFileName,
        String transcript,
        JsonNode summaryJson,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
