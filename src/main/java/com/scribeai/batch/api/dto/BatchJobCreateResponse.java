package com.scribeai.batch.api.dto;

import com.scribeai.document.domain.DocumentStatus;

public record BatchJobCreateResponse(
        Long documentId,
        DocumentStatus status,
        String originalFileName
) {
}
