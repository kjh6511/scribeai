package com.scribeai.rag.application.port;

import com.scribeai.rag.application.model.RagConversationTurn;
import com.scribeai.rag.application.model.RagSearchHit;

import java.util.List;

public interface RagAnswerProvider {
    String answer(String question, List<RagSearchHit> sources, List<RagConversationTurn> history);
}
