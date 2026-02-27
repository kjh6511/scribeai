package com.scribeai.realtime.api.dto;

public record AudioChunkMessage(
        Long sequence,
        String fileName,
        String contentType,
        String base64Audio
) {
}
