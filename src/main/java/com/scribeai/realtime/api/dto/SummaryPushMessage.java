package com.scribeai.realtime.api.dto;

import com.scribeai.summarize.application.SummaryResult;

public record SummaryPushMessage(
        Long documentId,
        SummaryResult summary
) {
}
