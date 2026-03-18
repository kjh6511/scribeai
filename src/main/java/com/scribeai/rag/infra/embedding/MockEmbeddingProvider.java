package com.scribeai.rag.infra.embedding;

import com.scribeai.rag.application.port.EmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 1536;

    // Mock 임베딩 생성
    @Override
    public float[] embed(String text) {
        String safe = text == null ? "" : text;
        float[] vector = new float[DIMENSION];

        String[] tokens = safe.split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int idx = Math.floorMod(hashToken(token), DIMENSION);
            vector[idx] += 1.0f;
        }

        normalize(vector);
        return vector;
    }

    private int hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            int value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (digest[i] & 0xff);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            return token.hashCode();
        }
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
