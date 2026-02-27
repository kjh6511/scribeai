package com.scribeai.summarize.application;

public interface SummarizeProvider {
    default SummaryResult summarize(String transcript) {
        return summarize(transcript, null);
    }

    SummaryResult summarize(String transcript, String titleHint);
}
