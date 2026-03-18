package com.scribeai.rag.application.model;

public record RagSearchHit(
        int chunkIndex,
        String content,
        double score
) {
}
