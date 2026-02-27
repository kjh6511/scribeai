package com.scribeai.rag.application;

import com.scribeai.common.text.TranscriptPreprocessor;
import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentStatus;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.rag.api.dto.RagAnswerResult;
import com.scribeai.rag.infra.RagChunkStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class RagService {

    private final DocumentRepository documentRepository;
    private final EmbeddingProvider embeddingProvider;
    private final RagAnswerProvider ragAnswerProvider;
    private final RagConversationMemoryStore conversationMemoryStore;
    private final RagChunkStore ragChunkStore;
    private final TranscriptPreprocessor transcriptPreprocessor;

    @Value("${rag.chunk-size:1000}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:150}")
    private int chunkOverlap;

    @Value("${rag.default-top-k:5}")
    private int defaultTopK;

    // 전사 인덱싱
    @Transactional
    public int indexDocumentTranscript(Long documentId, boolean forceRebuild) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));

        if (document.getStatus() != DocumentStatus.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "Document is not DONE: " + document.getStatus());
        }

        String transcript = document.getTranscript();
        if (transcript == null || transcript.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "No transcript to index");
        }

        int existingCount = ragChunkStore.countByDocumentId(documentId);
        if (!forceRebuild && existingCount > 0) {
            return existingCount;
        }
        //문서 청크
        List<String> chunks = chunkText(transcript);
        //청크 임베딩
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            embeddings.add(embeddingProvider.embed(chunk));
        }

        ragChunkStore.deleteByDocumentId(documentId);
        ragChunkStore.saveAll(documentId, chunks, embeddings);

        return chunks.size();
    }

    // 기본 인덱싱: 기존 인덱스가 있으면 재사용
    @Transactional
    public int indexDocumentTranscript(Long documentId) {
        return indexDocumentTranscript(documentId, false);
    }

    // 질문 검색
    @Transactional(readOnly = true)
    public List<RagSearchHit> search(Long documentId, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "query is required");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        if (document.getStatus() != DocumentStatus.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "Document is not DONE: " + document.getStatus());
        }

        int limit = sanitizeTopK(topK);
        float[] queryEmbedding = embeddingProvider.embed(query.trim());
        return ragChunkStore.searchByDocumentId(documentId, queryEmbedding, limit);
    }

    // RAG 답변 생성
    @Transactional
    public RagAnswerResult ask(Long documentId, String question, Integer topK, Boolean autoIndex) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "question is required");
        }

        validateDocumentReady(documentId);

        boolean indexedNow = false;
        boolean enableAutoIndex = autoIndex == null || autoIndex;
        if (ragChunkStore.countByDocumentId(documentId) == 0) {
            if (!enableAutoIndex) {
                throw new ResponseStatusException(BAD_REQUEST, "RAG index not found. Run /rag/index first or set autoIndex=true");
            }
            indexDocumentTranscript(documentId);
            indexedNow = true;
        }

        int limit = sanitizeTopK(topK);
        List<RagSearchHit> hits = search(documentId, question, limit);
        if (hits.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No related chunks found for this question");
        }

        String normalizedQuestion = question.trim();
        List<RagConversationTurn> history = conversationMemoryStore.getRecent(documentId);
        String answer = ragAnswerProvider.answer(normalizedQuestion, hits, history);
        conversationMemoryStore.append(documentId, normalizedQuestion, answer);
        return new RagAnswerResult(documentId, normalizedQuestion, answer, limit, hits, indexedNow);
    }

    // 대화 메모리 초기화
    public void clearConversation(Long documentId) {
        if (documentId == null) {
            return;
        }
        conversationMemoryStore.clear(documentId);
    }

    private void validateDocumentReady(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        if (document.getStatus() != DocumentStatus.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "Document is not DONE: " + document.getStatus());
        }
    }

    private int sanitizeTopK(Integer topK) {
        if (topK == null) {
            return Math.max(defaultTopK, 1);
        }
        return Math.min(Math.max(topK, 1), 20);
    }
    //텍스트 청크
    private List<String> chunkText(String transcript) {
        String normalized = transcriptPreprocessor.clean(transcript);
        if (normalized.isBlank()) {
            return List.of();
        }

        int safeChunkSize = Math.max(chunkSize, 300);
        int safeOverlap = Math.max(Math.min(chunkOverlap, safeChunkSize / 2), 0);
        int step = safeChunkSize - safeOverlap;

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + safeChunkSize, normalized.length());
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

}
