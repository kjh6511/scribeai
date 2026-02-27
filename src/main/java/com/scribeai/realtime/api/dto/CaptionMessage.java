package com.scribeai.realtime.api.dto;

public record CaptionMessage(
        Long documentId,
        Long sequence,
        String text,
        String language
) {
}
