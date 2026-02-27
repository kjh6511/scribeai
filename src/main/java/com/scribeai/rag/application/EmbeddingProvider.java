package com.scribeai.rag.application;

public interface EmbeddingProvider {
    float[] embed(String text);
}
