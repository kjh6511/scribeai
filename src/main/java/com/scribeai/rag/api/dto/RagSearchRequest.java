package com.scribeai.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RagSearchRequest(
        @NotNull
        @JsonAlias("jobId")
        Long documentId,
        @NotBlank String query,
        Integer topK
) {
}
