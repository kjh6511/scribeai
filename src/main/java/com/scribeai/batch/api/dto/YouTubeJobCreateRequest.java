package com.scribeai.batch.api.dto;

import jakarta.validation.constraints.NotBlank;

public record YouTubeJobCreateRequest(
        @NotBlank String url
) {
}
