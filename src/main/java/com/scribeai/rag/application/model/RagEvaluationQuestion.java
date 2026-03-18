package com.scribeai.rag.application.model;

import java.util.List;

public record RagEvaluationQuestion(
        String question,
        List<Integer> expectedChunkIndexes
) {
}

