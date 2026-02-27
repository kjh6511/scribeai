package com.scribeai.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_summaries")
public class DocumentSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long documentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summaryJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentSummary() {
    }

    private DocumentSummary(Long documentId, String summaryJson) {
        this.documentId = documentId;
        this.summaryJson = summaryJson;
    }
    //
    public static DocumentSummary create(Long documentId, String summaryJson) {
        return new DocumentSummary(documentId, summaryJson);
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

    public Long getDocumentId() {
        return documentId;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void changeSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }
}

