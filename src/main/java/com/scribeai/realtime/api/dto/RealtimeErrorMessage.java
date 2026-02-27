package com.scribeai.realtime.api.dto;

public record RealtimeErrorMessage(
        Long documentId,
        String message
) {
}
