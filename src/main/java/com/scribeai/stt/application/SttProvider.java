package com.scribeai.stt.application;

public interface SttProvider {
    SttResult transcribe(String fileName, byte[] audioBytes, String contentType);
}
