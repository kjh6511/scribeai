package com.scribeai.common.text;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TranscriptPreprocessor {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。！？])\\s+|\\n+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern BRACKET_NOISE = Pattern.compile("\\[(음악|박수|웃음|침묵|노이즈|music|applause|laughter)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN_NOISE = Pattern.compile("\\b(ㅋㅋ+|ㅎㅎ+|ah+|uh+|um+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_NOISE = Pattern.compile("[♪♬~]+");
    private static final Pattern REPEATED_SHORT_WORD = Pattern.compile("\\b([\\p{L}\\p{N}]{1,8})(\\s+\\1){2,}\\b", Pattern.CASE_INSENSITIVE);

    // 전사 전처리
    public String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String normalized = normalize(raw);
        String[] parts = SENTENCE_SPLIT.split(normalized);
        List<String> kept = new ArrayList<>();
        String lastKey = "";

        for (String part : parts) {
            String cleaned = cleanSentence(part);
            if (cleaned.isBlank()) {
                continue;
            }

            String key = normalizeKey(cleaned);
            if (key.isBlank()) {
                continue;
            }

            // 연속 중복 문장 제거
            if (key.equals(lastKey)) {
                continue;
            }

            // 매우 짧은 반복 구절 제거
            if (key.length() <= 12 && !kept.isEmpty()) {
                String prev = normalizeKey(kept.get(kept.size() - 1));
                if (prev.equals(key)) {
                    continue;
                }
            }

            kept.add(cleaned);
            lastKey = key;
        }

        if (kept.isEmpty()) {
            return "";
        }
        return String.join(" ", kept);
    }

    private String cleanSentence(String sentence) {
        String value = normalize(sentence);
        value = BRACKET_NOISE.matcher(value).replaceAll(" ");
        value = TOKEN_NOISE.matcher(value).replaceAll(" ");
        value = SYMBOL_NOISE.matcher(value).replaceAll(" ");
        value = REPEATED_SHORT_WORD.matcher(value).replaceAll("$1");
        value = collapseConsecutiveToken(value);
        return normalize(value);
    }

    private String collapseConsecutiveToken(String text) {
        String[] tokens = text.split(" ");
        List<String> compact = new ArrayList<>(tokens.length);
        String prev = "";
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (token.equals(prev)) {
                continue;
            }
            compact.add(token);
            prev = token;
        }
        return String.join(" ", compact);
    }

    private String normalize(String value) {
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    private String normalizeKey(String value) {
        return value
                .toLowerCase()
                .replaceAll("[^\\p{L}\\p{N} ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

