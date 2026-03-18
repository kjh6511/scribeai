package com.scribeai.rag.application.port;

public interface EmbeddingProvider {
    float[] embed(String text);
}
