package com.scribeai.realtime.api.dto;

public record RealtimeTranscriptResponse(
        Long documentId,
        String transcript
) {
}
