package com.scribeai.realtime.api.dto;

import com.scribeai.document.domain.DocumentStatus;

public record RealtimeDocumentCreateResponse(
        Long documentId,
        DocumentStatus status
) {
}

