package com.scribeai.batch.infra;

import com.scribeai.batch.api.dto.YouTubeExtractedContent;
import com.scribeai.common.text.TitleSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
public class YouTubeSubtitleExtractor {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    private final String ytDlpCommand;
    private final String jsRuntime;
    private final String extraPath;
    private final int errorMessageMaxLength;
    private final List<String> subtitleLangs;
    private final TitleSanitizer titleSanitizer;

    public YouTubeSubtitleExtractor(
            @Value("${youtube.yt-dlp.command:yt-dlp}") String ytDlpCommand,
            @Value("${youtube.yt-dlp.js-runtime:node}") String jsRuntime,
            @Value("${youtube.yt-dlp.extra-path:/opt/homebrew/bin:/usr/local/bin}") String extraPath,
            @Value("${youtube.yt-dlp.error-message-max-length:2000}") int errorMessageMaxLength,
            @Value("${youtube.yt-dlp.subtitle-langs:ko,en,es}") String subtitleLangs,
            TitleSanitizer titleSanitizer
    ) {
        this.ytDlpCommand = ytDlpCommand;
        this.jsRuntime = jsRuntime;
        this.extraPath = extraPath;
        this.errorMessageMaxLength = errorMessageMaxLength;
        this.subtitleLangs = parseSubtitleLangs(subtitleLangs);
        this.titleSanitizer = titleSanitizer;
    }

    // URL 자막 + 제목 추출
    public YouTubeExtractedContent extractContent(String url) {
        if (!isYouTubeUrl(url)) {
            throw new ResponseStatusException(BAD_REQUEST, "Only YouTube URL is supported");
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("yt-sub-");
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to create temp directory", e);
        }

        try {
            //제목 추출
            String title = titleSanitizer.sanitize(extractTitle(url), 120);
            runYtDlp(tempDir, url);
            //자막 추출
            Path subtitleFile = findSubtitleFile(tempDir)
                    .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "No YouTube subtitle found"));
            String transcript = parseVtt(subtitleFile);
            return new YouTubeExtractedContent(title, transcript);
        } finally {//완료 후, 파일 삭제
            deleteRecursively(tempDir);
        }
    }
    //유튜브 주소 확인
    private boolean isYouTubeUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    //자막 추출
    private void runYtDlp(Path tempDir, String url) {
        ResponseStatusException lastError = null;
        for (String lang : subtitleLangs) {
            try {
                runYtDlpForLanguage(tempDir, url, lang);
                if (findSubtitleFile(tempDir).isPresent()) {
                    return;
                }
            } catch (ResponseStatusException e) {
                lastError = e;
                String reason = e.getReason();
                if (reason != null && reason.contains("HTTP Error 429")) {
                    throw e;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new ResponseStatusException(BAD_REQUEST, "No subtitle language available");
    }

    //제목 추출
    private String extractTitle(String url) {
        List<String> commandCandidates = buildCommandCandidates();
        List<String> startErrors = new ArrayList<>();

        for (String commandPath : commandCandidates) {
            List<String> command = List.of(
                    commandPath,
                    "--skip-download",
                    "--print", "title",
                    "--js-runtimes", jsRuntime,
                    url
            );
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", extraPath + (currentPath.isBlank() ? "" : ":" + currentPath));

            try {
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                if (exit == 0) {
                    String title = output.lines().findFirst().orElse("").trim();
                    if (!title.isBlank()) {
                        return title;
                    }
                    return "";
                }
            } catch (IOException e) {
                startErrors.add(commandPath + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }

        return startErrors.isEmpty() ? "" : "";
    }

    //값 정규화
    private List<String> parseSubtitleLangs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("ko", "en", "es");
        }
        List<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            String lang = token.trim();
            if (!lang.isBlank()) {
                values.add(lang);
            }
        }
        return values.isEmpty() ? List.of("ko", "en", "es") : values;
    }
    //해당 언어로 자막 추출
    private void runYtDlpForLanguage(Path tempDir, String url, String subtitleLang) {
        List<String> commandCandidates = buildCommandCandidates();
        List<String> startErrors = new ArrayList<>();

        for (String commandPath : commandCandidates) {
            List<String> command = List.of(
                    commandPath,
                    "--skip-download",
                    "--write-auto-subs",
                    "--write-subs",
                    "--sub-langs", subtitleLang,
                    "--sub-format", "vtt",
                    "--js-runtimes", jsRuntime,
                    "-o", "%(id)s.%(ext)s",
                    "--paths", tempDir.toAbsolutePath().toString(),
                    url
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", extraPath + (currentPath.isBlank() ? "" : ":" + currentPath));

            try {
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                if (exit != 0) {
                    String compactOutput = compact(output);
                    if (compactOutput.contains("HTTP Error 429")) {
                        throw new ResponseStatusException(
                                BAD_REQUEST,
                                "yt-dlp failed (429 Too Many Requests). Wait and retry later: " + compactOutput
                        );
                    }
                    throw new ResponseStatusException(BAD_REQUEST, "yt-dlp failed: " + compactOutput);
                }
                return;
            } catch (IOException e) {
                startErrors.add(commandPath + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "yt-dlp interrupted", e);
            }
        }

        throw new ResponseStatusException(
                INTERNAL_SERVER_ERROR,
                "yt-dlp is not available (" + String.join(" | ", startErrors) + ")"
        );
    }
    //추출 명령어
    private List<String> buildCommandCandidates() {
        List<String> candidates = new ArrayList<>();
        candidates.add(ytDlpCommand);
        candidates.add("/opt/homebrew/bin/yt-dlp");
        candidates.add("/usr/local/bin/yt-dlp");
        candidates.add("yt-dlp");
        return candidates;
    }
    //자막 파일 찾기
    private Optional<Path> findSubtitleFile(Path tempDir) {
        try (Stream<Path> stream = Files.walk(tempDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vtt"))
                    .findFirst();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to read subtitle file", e);
        }
    }

    //.vtt자막 파일을 텍스트로 변환
    private String parseVtt(Path vttPath) {
        try {
            List<String> lines = Files.readAllLines(vttPath, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("WEBVTT") || trimmed.startsWith("NOTE")) {
                    continue;
                }
                if (trimmed.contains("-->")) {
                    continue;
                }
                if (trimmed.matches("^\\d+$")) {
                    continue;
                }

                String plain = HTML_TAG_PATTERN.matcher(trimmed).replaceAll("").trim();
                if (!plain.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(plain);
                }
            }

            String transcript = sb.toString().trim();
            if (transcript.isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "Subtitle text is empty");
            }
            return transcript;
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to parse subtitle file", e);
        }
    }
    //파일 삭제
    private void deleteRecursively(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
    //에러 텍스트 정리
    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > errorMessageMaxLength
                ? oneLine.substring(0, errorMessageMaxLength) + "..."
                : oneLine;
    }

    //제목 과장 표현 제거
}
