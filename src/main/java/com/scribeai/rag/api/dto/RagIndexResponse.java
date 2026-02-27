package com.scribeai.rag.api.dto;

public record RagIndexResponse(
        Long documentId,
        int chunkCount
) {
}
