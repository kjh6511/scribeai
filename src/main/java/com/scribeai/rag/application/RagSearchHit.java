package com.scribeai.rag.application;

public record RagSearchHit(
        int chunkIndex,
        String content,
        double score
) {
}
