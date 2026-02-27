package com.scribeai.realtime.api;

import com.scribeai.realtime.api.dto.RealtimeTranscriptResponse;
import com.scribeai.realtime.api.dto.SummaryPushMessage;
import com.scribeai.realtime.application.RealtimeTranscriptionService;
import com.scribeai.summarize.application.SummaryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime/documents")
@RequiredArgsConstructor
public class RealtimeSummaryController {

    private final RealtimeTranscriptionService realtimeTranscriptionService;

    // 수동 요약 실행
    @PostMapping("/{documentId}/summaries")
    public SummaryPushMessage summarize(@PathVariable("documentId") Long documentId) {
        SummaryResult summary = realtimeTranscriptionService.summarizeNow(documentId);
        return new SummaryPushMessage(documentId, summary);
    }

    // 누적 transcript 조회
    @GetMapping("/{documentId}/transcript")
    public RealtimeTranscriptResponse transcript(@PathVariable("documentId") Long documentId) {
        return new RealtimeTranscriptResponse(documentId, realtimeTranscriptionService.getAccumulatedTranscript(documentId));
    }

    // 실시간 문서 상태 정리
    @DeleteMapping("/{documentId}")
    public void clear(@PathVariable("documentId") Long documentId) {
        realtimeTranscriptionService.clear(documentId);
    }
}
