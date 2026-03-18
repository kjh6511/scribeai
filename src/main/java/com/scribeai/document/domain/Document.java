package com.scribeai.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentSourceType sourceType;

    @Column(nullable = false)
    private String originalName;

    @Column(columnDefinition = "TEXT")
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Document() {
    }

    private Document(DocumentSourceType sourceType, String originalName, String sourceUrl, DocumentStatus status) {
        this.sourceType = sourceType;
        this.originalName = originalName;
        this.sourceUrl = sourceUrl;
        this.status = status;
    }
    //문서 작업 생성
    public static Document createPending(DocumentSourceType sourceType, String originalName, String sourceUrl) {
        return new Document(sourceType, originalName, sourceUrl, DocumentStatus.PENDING);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public DocumentSourceType getSourceType() {
        return sourceType;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markRunning() {
        ensureTransitionAllowed(DocumentStatus.RUNNING, DocumentStatus.PENDING);
        this.status = DocumentStatus.RUNNING;
        this.errorMessage = null;
    }

    public void prepareRetry() {
        ensureTransitionAllowed(DocumentStatus.PENDING, DocumentStatus.FAIL);
        this.status = DocumentStatus.PENDING;
        this.transcript = null;
        this.errorMessage = null;
    }

    public void markDone(String transcript) {
        ensureTransitionAllowed(DocumentStatus.DONE, DocumentStatus.RUNNING);
        this.status = DocumentStatus.DONE;
        this.transcript = transcript;
        this.errorMessage = null;
    }

    public void markFail(String errorMessage) {
        ensureTransitionAllowed(DocumentStatus.FAIL, DocumentStatus.PENDING, DocumentStatus.RUNNING);
        this.status = DocumentStatus.FAIL;
        this.errorMessage = errorMessage;
    }

    private void ensureTransitionAllowed(DocumentStatus next, DocumentStatus... allowedCurrentStatuses) {
        for (DocumentStatus allowed : allowedCurrentStatuses) {
            if (this.status == allowed) {
                return;
            }
        }
        throw new IllegalStateException("Invalid document status transition: " + this.status + " -> " + next);
    }
}
