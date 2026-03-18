package com.scribeai.rag.application.service;

import com.scribeai.common.text.TranscriptPreprocessor;
import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentStatus;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.rag.api.dto.RagAnswerResult;
import com.scribeai.rag.application.model.RagConversationTurn;
import com.scribeai.rag.application.model.RagPipelineMode;
import com.scribeai.rag.application.model.RagSearchHit;
import com.scribeai.rag.application.port.EmbeddingProvider;
import com.scribeai.rag.application.port.RagAnswerProvider;
import com.scribeai.rag.application.port.RagRerankProvider;
import com.scribeai.rag.infra.store.RagChunkStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class RagService {

    private final DocumentRepository documentRepository;
    private final EmbeddingProvider embeddingProvider;
    private final RagAnswerProvider ragAnswerProvider;
    private final RagRerankProvider ragRerankProvider;
    private final RagConversationMemoryStore conversationMemoryStore;
    private final RagChunkStore ragChunkStore;
    private final TranscriptPreprocessor transcriptPreprocessor;

    @Value("${rag.chunk-size:1000}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:150}")
    private int chunkOverlap;

    @Value("${rag.search.final-top-k:${rag.default-top-k:5}}")
    private int defaultFinalTopK;

    @Value("${rag.search.vector-retrieve-top-k:${rag.search.vector-top-k:12}}")
    private int vectorRetrieveTopK;

    @Value("${rag.search.keyword-retrieve-top-k:${rag.search.keyword-top-k:12}}")
    private int keywordRetrieveTopK;

    @Value("${rag.search.rrf-k:60}")
    private int rrfK;

    @Value("${rag.search.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${rag.search.keyword-weight:1.0}")
    private double keywordWeight;

    @Value("${rag.search.hybrid-candidate-top-k:${rag.rerank.candidate-top-k:16}}")
    private int hybridCandidateTopK;

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
    public List<RagSearchHit> search(Long documentId, String query, Integer requestedFinalTopK) {
        return search(documentId, query, requestedFinalTopK, RagPipelineMode.HYBRID_RERANK);
    }

    // 질문 검색(모드별)
    @Transactional(readOnly = true)
    public List<RagSearchHit> search(
            Long documentId,
            String query,
            Integer requestedFinalTopK,
            RagPipelineMode pipelineMode
    ) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "query is required");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        if (document.getStatus() != DocumentStatus.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "Document is not DONE: " + document.getStatus());
        }

        int finalTopK = sanitizeFinalTopK(requestedFinalTopK);
        String normalizedQuery = query.trim();
        RagPipelineMode mode = pipelineMode == null ? RagPipelineMode.HYBRID_RERANK : pipelineMode;

        int safeVectorRetrieveTopK = Math.max(vectorRetrieveTopK, finalTopK);
        int safeKeywordRetrieveTopK = Math.max(keywordRetrieveTopK, finalTopK);
        int safeHybridCandidateTopK = Math.max(hybridCandidateTopK, finalTopK);

        //벡터 검색
        float[] queryEmbedding = embeddingProvider.embed(normalizedQuery);
        List<RagSearchHit> vectorHits = ragChunkStore.searchVectorByDocumentId(documentId, queryEmbedding, safeVectorRetrieveTopK);

        if (mode == RagPipelineMode.VECTOR_ONLY) {
            return vectorHits.size() <= finalTopK ? vectorHits : vectorHits.subList(0, finalTopK);
        }

        //키워드 검색
        List<RagSearchHit> keywordHits = ragChunkStore.searchKeywordByDocumentId(documentId, normalizedQuery, safeKeywordRetrieveTopK);
        //하이브리드 결합
        List<RagSearchHit> fusedHits = fuseByRrf(vectorHits, keywordHits, safeHybridCandidateTopK);
        if (fusedHits.isEmpty()) {
            return List.of();
        }

        if (mode == RagPipelineMode.HYBRID_ONLY) {
            return fusedHits.size() <= finalTopK ? fusedHits : fusedHits.subList(0, finalTopK);
        }

        //리랭크
        return ragRerankProvider.rerank(normalizedQuery, fusedHits, finalTopK);
    }

    // RAG 답변 생성
    @Transactional
    public RagAnswerResult ask(Long documentId, String question, Integer requestedFinalTopK, Boolean autoIndex) {
        return ask(documentId, question, requestedFinalTopK, autoIndex, RagPipelineMode.HYBRID_RERANK);
    }

    // RAG 답변 생성(모드별)
    @Transactional
    public RagAnswerResult ask(
            Long documentId,
            String question,
            Integer requestedFinalTopK,
            Boolean autoIndex,
            RagPipelineMode pipelineMode
    ) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "question is required");
        }
        //상태값 점검
        validateDocumentReady(documentId);
        //인덱싱이 없으면 인덱싱
        boolean indexedNow = false;
        boolean enableAutoIndex = autoIndex == null || autoIndex;
        if (ragChunkStore.countByDocumentId(documentId) == 0) {
            if (!enableAutoIndex) {
                throw new ResponseStatusException(BAD_REQUEST, "RAG index not found. Run /rag/index first or set autoIndex=true");
            }
            indexDocumentTranscript(documentId);
            indexedNow = true;
        }
        //rag 검색
        int finalTopK = sanitizeFinalTopK(requestedFinalTopK);
        List<RagSearchHit> hits = search(documentId, question, finalTopK, pipelineMode);
        if (hits.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No related chunks found for this question");
        }

        String normalizedQuestion = question.trim();
        //이전 대화 기록(인메모리)
        List<RagConversationTurn> history = conversationMemoryStore.getRecent(documentId);
        //답변 결과
        String answer = ragAnswerProvider.answer(normalizedQuestion, hits, history);
        //대화 기록 추가
        conversationMemoryStore.append(documentId, normalizedQuestion, answer);
        return new RagAnswerResult(documentId, normalizedQuestion, answer, finalTopK, hits, indexedNow);
    }

    // 대화 메모리 초기화
    public void clearConversation(Long documentId) {
        if (documentId == null) {
            return;
        }
        conversationMemoryStore.clear(documentId);
    }

    //자료 상태값 검사
    private void validateDocumentReady(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        if (document.getStatus() != DocumentStatus.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "Document is not DONE: " + document.getStatus());
        }
    }

    private int sanitizeFinalTopK(Integer requestedFinalTopK) {
        if (requestedFinalTopK == null) {
            return Math.max(defaultFinalTopK, 1);
        }
        return Math.min(Math.max(requestedFinalTopK, 1), 20);
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

    // 벡터/키워드 결과 RRF 결합
    private List<RagSearchHit> fuseByRrf(
            List<RagSearchHit> vectorHits,
            List<RagSearchHit> keywordHits,
            int candidateLimit
    ) {
        int safeRrfK = Math.max(rrfK, 1);
        double safeVectorWeight = Math.max(vectorWeight, 0.0d);
        double safeKeywordWeight = Math.max(keywordWeight, 0.0d);

        Map<Integer, ScoreHolder> map = new HashMap<>();
        applyRrf(map, vectorHits, safeRrfK, safeVectorWeight);
        applyRrf(map, keywordHits, safeRrfK, safeKeywordWeight);

        List<RagSearchHit> fused = map.values().stream()
                .map(holder -> new RagSearchHit(holder.chunkIndex, holder.content, holder.score))
                .sorted(Comparator.comparingDouble(RagSearchHit::score).reversed())
                .toList();

        int safeLimit = Math.max(candidateLimit, 1);
        return fused.size() <= safeLimit ? fused : fused.subList(0, safeLimit);
    }

    private void applyRrf(
            Map<Integer, ScoreHolder> map,
            List<RagSearchHit> hits,
            int safeRrfK,
            double weight
    ) {
        if (hits == null || hits.isEmpty() || weight <= 0.0d) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            RagSearchHit hit = hits.get(i);
            int rank = i + 1;
            double added = weight / (safeRrfK + rank);

            ScoreHolder holder = map.computeIfAbsent(
                    hit.chunkIndex(),
                    ignored -> new ScoreHolder(hit.chunkIndex(), hit.content())
            );
            holder.score += added;
            if ((holder.content == null || holder.content.isBlank()) && hit.content() != null) {
                holder.content = hit.content();
            }
        }
    }

    private static final class ScoreHolder {
        private final int chunkIndex;
        private String content;
        private double score;

        private ScoreHolder(int chunkIndex, String content) {
            this.chunkIndex = chunkIndex;
            this.content = content;
        }
    }

}
