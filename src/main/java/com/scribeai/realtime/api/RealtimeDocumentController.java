package com.scribeai.realtime.api;

import com.scribeai.document.domain.Document;
import com.scribeai.document.domain.DocumentSourceType;
import com.scribeai.document.repository.DocumentRepository;
import com.scribeai.realtime.api.dto.RealtimeDocumentCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime/documents")
@RequiredArgsConstructor
public class RealtimeDocumentController {

    private final DocumentRepository documentRepository;

    // 실시간 문서 생성
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RealtimeDocumentCreateResponse createRealtimeDocument() {
        Document saved = documentRepository.save(
                Document.createPending(DocumentSourceType.REALTIME, "realtime-document", null)
        );
        return new RealtimeDocumentCreateResponse(saved.getId(), saved.getStatus());
    }
}

