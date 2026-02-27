package com.scribeai.summarize.application;

import java.util.List;

public record SummaryResult(
        String mode,
        String packType,
        String title,
        String overview,
        List<SectionNote> sections,
        String finalSummary,
        List<String> keywords,
        List<String> suggestedQuestions,
        String aiComment
) {
    public record SectionNote(
            String heading,
            String notes
    ) {
    }
}
