package com.scribeai.stt.infra;

import com.scribeai.stt.application.SttProvider;
import com.scribeai.stt.application.SttResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "stt", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockSttProvider implements SttProvider {

    // Mock STT 변환
    @Override
    public SttResult transcribe(String fileName, byte[] audioBytes, String contentType) {
        String safeFileName = fileName == null || fileName.isBlank() ? "unknown" : fileName;
        String text = "[MOCK STT] Transcribed from file: " + safeFileName;
        return new SttResult(text, "ko");
    }
}
