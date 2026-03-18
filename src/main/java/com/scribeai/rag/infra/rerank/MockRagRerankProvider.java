package com.scribeai.rag.infra.rerank;

import com.scribeai.rag.application.port.RagRerankProvider;
import com.scribeai.rag.application.model.RagSearchHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "rag.rerank", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockRagRerankProvider implements RagRerankProvider {

    // 간단 규칙 기반 리랭크
    @Override
    public List<RagSearchHit> rerank(String query, List<RagSearchHit> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(query);
        List<RagSearchHit> rescored = new ArrayList<>(candidates.size());
        for (RagSearchHit candidate : candidates) {
            Set<String> contentTokens = tokenize(candidate.content());
            int overlap = 0;
            for (String token : queryTokens) {
                if (contentTokens.contains(token)) {
                    overlap++;
                }
            }

            double lexicalScore = queryTokens.isEmpty() ? 0.0 : (double) overlap / (double) queryTokens.size();
            double hybridScore = candidate.score();
            double rerankScore = (hybridScore * 0.75d) + (lexicalScore * 0.25d);

            rescored.add(new RagSearchHit(candidate.chunkIndex(), candidate.content(), rerankScore));
        }

        rescored.sort(Comparator.comparingDouble(RagSearchHit::score).reversed());
        int safeTopN = Math.max(topN, 1);
        return rescored.size() <= safeTopN ? rescored : rescored.subList(0, safeTopN);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}가-힣]+", " ")
                .trim()
                .split("\\s+");

        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
