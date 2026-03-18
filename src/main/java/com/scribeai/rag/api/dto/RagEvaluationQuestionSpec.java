package com.scribeai.rag.api.dto;

import java.util.List;

public record RagEvaluationQuestionSpec(
        String question,
        List<Integer> expectedChunkIndexes
) {
}

