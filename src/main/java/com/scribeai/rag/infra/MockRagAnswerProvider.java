package com.scribeai.rag.infra;

import com.scribeai.rag.application.RagAnswerProvider;
import com.scribeai.rag.application.RagConversationTurn;
import com.scribeai.rag.application.RagSearchHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "rag.answer", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockRagAnswerProvider implements RagAnswerProvider {

    // Mock 질의응답 생성
    @Override
    public String answer(String question, List<RagSearchHit> sources, List<RagConversationTurn> history) {
        if (sources == null || sources.isEmpty()) {
            return "관련 근거 청크를 찾지 못해 답변을 생성하지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("질문에 맞는 내용을 전사에서 찾아 정리했습니다. ");

        int max = Math.min(sources.size(), 3);
        for (int i = 0; i < max; i++) {
            RagSearchHit hit = sources.get(i);
            String content = hit.content() == null ? "" : hit.content();
            String snippet = content.length() > 140 ? content.substring(0, 140) + "..." : content;
            sb.append("[근거 ").append(hit.chunkIndex()).append("] ").append(snippet);
            if (i < max - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
