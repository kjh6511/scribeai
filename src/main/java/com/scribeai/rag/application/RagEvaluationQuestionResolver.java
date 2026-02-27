package com.scribeai.rag.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagEvaluationQuestionResolver {

    @Value("${rag.evaluation.max-questions:5}")
    private int maxQuestions;

    // 평가 질문 정규화
    public List<String> resolve(List<String> rawQuestions) {
        List<String> questions = rawQuestions == null
                ? List.of()
                : rawQuestions.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        if (questions.isEmpty()) {
            questions = defaultQuestions();
        }

        int limit = Math.max(maxQuestions, 1);
        if (questions.size() > limit) {
            return questions.subList(0, limit);
        }
        return questions;
    }

    private List<String> defaultQuestions() {
        return List.of(
                "이 영상의 핵심 주제는 무엇인가요?",
                "발표자가 가장 강조한 근거는 무엇인가요?",
                "핵심 내용을 한 문단으로 정리해 주세요.",
                "실제 적용 포인트가 있다면 무엇인가요?",
                "오해하기 쉬운 부분이나 주의점은 무엇인가요?"
        );
    }
}
