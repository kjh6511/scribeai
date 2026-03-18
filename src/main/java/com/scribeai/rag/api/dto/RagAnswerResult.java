package com.scribeai.rag.api.dto;

import com.scribeai.rag.application.model.RagSearchHit;

import java.util.List;

public record RagAnswerResult(
        Long documentId,
        String question,
        String answer,
        int topK,
        List<RagSearchHit> sources,
        boolean indexedNow
) {
}
