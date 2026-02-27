package com.scribeai.batch.api;

import com.scribeai.batch.api.dto.YouTubeJobCreateRequest;
import com.scribeai.batch.api.dto.BatchJobCreateResponse;
import com.scribeai.batch.api.dto.BatchJobDetailResponse;
import com.scribeai.batch.application.BatchJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/batch/jobs")
@RequiredArgsConstructor
public class BatchJobController {

    private final BatchJobService batchJobService;

    // 파일 배치 작업 생성
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchJobCreateResponse createJob(@RequestPart("file") MultipartFile file) {
        return batchJobService.createJob(file);
    }

    // YouTube 배치 작업 생성
    @PostMapping("/youtube")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchJobCreateResponse createYouTubeJob(@Valid @RequestBody YouTubeJobCreateRequest request) {
        return batchJobService.createYouTubeJob(request.url());
    }

    // 문서 재시도
    @PostMapping("/{documentId}/retry")
    public BatchJobCreateResponse retryDocument(@PathVariable("documentId") Long documentId) {
        return batchJobService.retryJob(documentId);
    }

    // 문서 단건 조회
    @GetMapping("/{documentId}")
    public BatchJobDetailResponse getDocument(@PathVariable("documentId") Long documentId) {
        return batchJobService.getJob(documentId);
    }
}
