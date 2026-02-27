package com.scribeai.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RagEvaluationRunRequest(
        @NotNull
        @JsonAlias("jobId")
        Long documentId,
        List<String> questions,
        Integer topK,
        Boolean autoIndex
) {
}
