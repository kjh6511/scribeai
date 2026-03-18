package com.scribeai.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RagEvaluationRunRequest(
        @NotNull
        @JsonAlias("jobId")
        Long documentId,
        List<String> questions,
        List<RagEvaluationQuestionSpec> questionSet,
        List<String> compareModes,
        Integer topK,
        Boolean autoIndex
) {
}
