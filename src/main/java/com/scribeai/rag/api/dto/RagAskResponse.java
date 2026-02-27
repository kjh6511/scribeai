package com.scribeai.rag.api.dto;

import java.util.List;

public record RagAskResponse(
        Long documentId,
        String question,
        String answer,
        int topK,
        boolean indexedNow,
        List<RagSourceItem> sources
) {
    public record RagSourceItem(
            int chunkIndex,
            String content,
            double score
    ) {
    }
}
