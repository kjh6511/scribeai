package com.scribeai.rag.application;

import java.util.List;

public interface RagAnswerProvider {
    String answer(String question, List<RagSearchHit> sources, List<RagConversationTurn> history);
}
