package com.scribeai.rag.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RagConversationMemoryStore {

    private final Map<Long, Deque<RagConversationTurn>> memoryByDocumentId = new ConcurrentHashMap<>();

    @Value("${rag.conversation.max-turns:4}")
    private int maxTurns;

    @Value("${rag.conversation.ttl-minutes:60}")
    private int ttlMinutes;

    // 최근 대화 조회
    public List<RagConversationTurn> getRecent(Long documentId) {
        Deque<RagConversationTurn> deque = memoryByDocumentId.get(documentId);
        if (deque == null) {
            return List.of();
        }
        //동시 검색 상황 방지 - 묶어서 순서대로 하도록
        synchronized (deque) {
            pruneExpired(deque);
            if (deque.isEmpty()) {
                memoryByDocumentId.remove(documentId);
                return List.of();
            }
            return List.copyOf(deque);
        }
    }

    // 대화 추가
    public void append(Long documentId, String question, String answer) {
        if (documentId == null || question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }

        Deque<RagConversationTurn> deque = memoryByDocumentId.computeIfAbsent(documentId, id -> new ArrayDeque<>());
        synchronized (deque) {
            pruneExpired(deque);
            deque.addLast(new RagConversationTurn(question.trim(), answer.trim(), Instant.now()));
            int safeMaxTurns = Math.max(maxTurns, 1);
            while (deque.size() > safeMaxTurns) {
                deque.removeFirst();
            }
        }
    }

    // 문서 대화 초기화
    public void clear(Long documentId) {
        memoryByDocumentId.remove(documentId);
    }

    private void pruneExpired(Deque<RagConversationTurn> deque) {
        long safeTtl = Math.max(ttlMinutes, 1);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(safeTtl));
        while (!deque.isEmpty()) {
            RagConversationTurn first = deque.peekFirst();
            if (first == null || first.askedAt() == null || first.askedAt().isBefore(threshold)) {
                deque.removeFirst();
                continue;
            }
            break;
        }
    }
}
