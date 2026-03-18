package com.scribeai.rag.application.service;

import com.scribeai.rag.api.dto.RagEvaluationQuestionSpec;
import com.scribeai.rag.application.model.RagEvaluationQuestion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagEvaluationQuestionResolver {

    @Value("${rag.evaluation.max-questions:5}")
    private int maxQuestions;

    // 평가 질문 정규화 (질문셋 + 정답 청크 라벨 지원)
    public List<RagEvaluationQuestion> resolve(List<String> rawQuestions, List<RagEvaluationQuestionSpec> questionSet) {
        List<RagEvaluationQuestion> resolved = fromQuestionSet(questionSet);
        if (resolved.isEmpty()) {
            resolved = fromRawQuestions(rawQuestions);
        }
        if (resolved.isEmpty()) {
            resolved = defaultQuestions();
        }

        int limit = Math.max(maxQuestions, 1);
        if (resolved.size() > limit) {
            return resolved.subList(0, limit);
        }
        return resolved;
    }

    private List<RagEvaluationQuestion> fromRawQuestions(List<String> rawQuestions) {
        if (rawQuestions == null || rawQuestions.isEmpty()) {
            return List.of();
        }
        List<RagEvaluationQuestion> questions = new ArrayList<>();
        for (String value : rawQuestions) {
            String trimmed = value == null ? "" : value.trim();
            if (!trimmed.isBlank()) {
                questions.add(new RagEvaluationQuestion(trimmed, List.of()));
            }
        }
        return deduplicateByQuestion(questions);
    }

    private List<RagEvaluationQuestion> fromQuestionSet(List<RagEvaluationQuestionSpec> questionSet) {
        if (questionSet == null || questionSet.isEmpty()) {
            return List.of();
        }
        List<RagEvaluationQuestion> questions = new ArrayList<>();
        for (RagEvaluationQuestionSpec spec : questionSet) {
            if (spec == null) {
                continue;
            }
            String question = spec.question() == null ? "" : spec.question().trim();
            if (question.isBlank()) {
                continue;
            }
            List<Integer> expected = normalizeExpectedChunkIndexes(spec.expectedChunkIndexes());
            questions.add(new RagEvaluationQuestion(question, expected));
        }
        return deduplicateByQuestion(questions);
    }

    private List<Integer> normalizeExpectedChunkIndexes(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value >= 0)
                .distinct()
                .toList();
    }

    private List<RagEvaluationQuestion> deduplicateByQuestion(List<RagEvaluationQuestion> values) {
        Map<String, RagEvaluationQuestion> map = new LinkedHashMap<>();
        for (RagEvaluationQuestion value : values) {
            String key = value.question().trim();
            if (!map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return new ArrayList<>(map.values());
    }

    private List<RagEvaluationQuestion> defaultQuestions() {
        return List.of(
                new RagEvaluationQuestion("이 영상의 핵심 주제는 무엇인가요?", List.of()),
                new RagEvaluationQuestion("발표자가 가장 강조한 근거는 무엇인가요?", List.of()),
                new RagEvaluationQuestion("핵심 내용을 한 문단으로 정리해 주세요.", List.of()),
                new RagEvaluationQuestion("실제 적용 포인트가 있다면 무엇인가요?", List.of()),
                new RagEvaluationQuestion("오해하기 쉬운 부분이나 주의점은 무엇인가요?", List.of())
        );
    }
}
