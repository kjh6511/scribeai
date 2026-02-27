package com.scribeai.realtime.api;

import com.scribeai.realtime.api.dto.AudioChunkMessage;
import com.scribeai.realtime.application.RealtimeTranscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class RealtimeWsController {

    private final RealtimeTranscriptionService realtimeTranscriptionService;

    // 오디오 청크 수신
    @MessageMapping("/realtime/{documentId}/audio")
    public void handleAudio(
            @DestinationVariable("documentId") Long documentId,
            AudioChunkMessage message
    ) {
        realtimeTranscriptionService.handleChunk(documentId, message);
    }

    // 실시간 문서 수집 종료
    @MessageMapping("/realtime/{documentId}/finish")
    public void finish(@DestinationVariable("documentId") Long documentId) {
        realtimeTranscriptionService.finish(documentId);
    }
}
