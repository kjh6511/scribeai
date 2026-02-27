package com.scribeai.common.text;

import org.springframework.stereotype.Component;

@Component
public class TitleSanitizer {

    // 제목 힌트 정제
    public String sanitize(String title, int maxLength) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String cleaned = title
                .replaceAll("#\\S+", " ")
                .replaceAll("[\\p{So}\\p{Cn}]+", " ")
                .replaceAll("(?i)\\b(레전드|충격|미쳤다|실화냐|대박|핵꿀팁|공식|브이로그|리액션|shorts|must watch|breaking|official)\\b", " ")
                .replaceAll("\\[[^\\]]+]", " ")
                .replaceAll("\\([^\\)]*채널[^\\)]*\\)", " ")
                .replaceAll("\\([^\\)]*\\)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        int safeMaxLength = Math.max(maxLength, 1);
        return cleaned.length() > safeMaxLength ? cleaned.substring(0, safeMaxLength) : cleaned;
    }
}
