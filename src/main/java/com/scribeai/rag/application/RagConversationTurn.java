package com.scribeai.rag.application;

import java.time.Instant;

public record RagConversationTurn(
        String question,
        String answer,
        Instant askedAt
) {
}
