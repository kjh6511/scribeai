package com.scribeai.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RagAskRequest(
        @NotNull
        @JsonAlias("jobId")
        Long documentId,
        @NotBlank String question,
        Integer topK,
        Boolean autoIndex
) {
}
