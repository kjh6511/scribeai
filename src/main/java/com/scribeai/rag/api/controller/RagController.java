package com.scribeai.rag.api.controller;

import com.scribeai.rag.api.dto.RagAskRequest;
import com.scribeai.rag.api.dto.RagAskResponse;
import com.scribeai.rag.api.dto.RagAnswerResult;
import com.scribeai.rag.api.dto.RagEvaluationRunRequest;
import com.scribeai.rag.api.dto.RagEvaluationRunResponse;
import com.scribeai.rag.api.dto.RagIndexResponse;
import com.scribeai.rag.api.dto.RagSearchRequest;
import com.scribeai.rag.api.dto.RagSearchResponse;
import com.scribeai.rag.application.service.RagEvaluationService;
import com.scribeai.rag.application.model.RagSearchHit;
import com.scribeai.rag.application.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final RagEvaluationService ragEvaluationService;

    // 전사 인덱싱 (호환: batch path)
    @PostMapping("/index/batch/{jobId}")
    public RagIndexResponse indexBatch(
            @PathVariable("jobId") Long documentId,
            @RequestParam(value = "force", defaultValue = "false") boolean force
    ) {
        int chunkCount = ragService.indexDocumentTranscript(documentId, force);
        return new RagIndexResponse(documentId, chunkCount);
    }

    // 전사 인덱싱 (권장: document path)
    @PostMapping("/index/documents/{documentId}")
    public RagIndexResponse indexDocument(
            @PathVariable("documentId") Long documentId,
            @RequestParam(value = "force", defaultValue = "false") boolean force
    ) {
        int chunkCount = ragService.indexDocumentTranscript(documentId, force);
        return new RagIndexResponse(documentId, chunkCount);
    }

    // 유사 청크 검색
    @PostMapping("/search")
    public RagSearchResponse search(@Valid @RequestBody RagSearchRequest request) {
        List<RagSearchHit> hits = ragService.search(request.documentId(), request.query(), request.topK());

        List<RagSearchResponse.RagSearchItem> items = hits.stream()
                .map(hit -> new RagSearchResponse.RagSearchItem(
                        hit.chunkIndex(),
                        hit.content(),
                        hit.score()
                ))
                .toList();

        return new RagSearchResponse(
                request.documentId(),
                request.query(),
                request.topK() == null ? items.size() : request.topK(),
                items
        );
    }

    // RAG 답변 생성
    @PostMapping("/ask")
    public RagAskResponse ask(@Valid @RequestBody RagAskRequest request) {
        RagAnswerResult result = ragService.ask(
                request.documentId(),
                request.question(),
                request.topK(), //몇개의 top으로 검색할건지. 기본 5
                request.autoIndex()
        );

        List<RagAskResponse.RagSourceItem> items = result.sources().stream()
                .map(hit -> new RagAskResponse.RagSourceItem(
                        hit.chunkIndex(),
                        hit.content(),
                        hit.score()
                ))
                .toList();

        return new RagAskResponse(
                result.documentId(),
                result.question(),
                result.answer(),
                result.topK(),
                result.indexedNow(),
                items
        );
    }

    // 문서 대화 메모리 초기화
    @PostMapping("/conversations/documents/{documentId}/clear")
    public void clearConversation(@PathVariable("documentId") Long documentId) {
        ragService.clearConversation(documentId);
    }

    // 평가 실행
    @PostMapping("/evaluations/run")
    public RagEvaluationRunResponse runEvaluation(@Valid @RequestBody RagEvaluationRunRequest request) {
        return ragEvaluationService.run(
                request.documentId(),
                request.questions(),
                request.questionSet(),
                request.compareModes(),
                request.topK(),
                request.autoIndex()
        );
    }
}
