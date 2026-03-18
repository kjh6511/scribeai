package com.scribeai.rag.application.model;

import java.time.Instant;

public record RagConversationTurn(
        String question,
        String answer,
        Instant askedAt
) {
}
