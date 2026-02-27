package com.scribeai.rag.api.dto;

import java.util.List;

public record RagSearchResponse(
        Long documentId,
        String query,
        int topK,
        List<RagSearchItem> items
) {
    public record RagSearchItem(
            int chunkIndex,
            String content,
            double score
    ) {
    }
}
