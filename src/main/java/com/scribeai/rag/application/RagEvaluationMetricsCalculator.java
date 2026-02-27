package com.scribeai.rag.application;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class RagEvaluationMetricsCalculator {

    // 나노초를 밀리초로 변환
    public long toMillis(long nanos) {
        return Math.max(nanos / 1_000_000L, 0L);
    }

    // 평균 지연 계산
    public long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return sum / values.size();
    }

    // p95 지연 계산
    public long p95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        int safeIndex = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(safeIndex);
    }
}
