package com.scribeai.rag.application.model;

public enum RagPipelineMode {
    VECTOR_ONLY,
    HYBRID_ONLY,
    HYBRID_RERANK;

    public static RagPipelineMode from(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID_RERANK;
        }
        return switch (value.trim().toUpperCase()) {
            case "VECTOR_ONLY" -> VECTOR_ONLY;
            case "HYBRID_ONLY" -> HYBRID_ONLY;
            case "HYBRID_RERANK" -> HYBRID_RERANK;
            default -> HYBRID_RERANK;
        };
    }
}

