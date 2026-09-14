package com.obdeadsoup.devpilot.knowledge.api.dto;

import com.obdeadsoup.devpilot.knowledge.application.KnowledgeDocumentView;
import com.obdeadsoup.devpilot.knowledge.domain.KnowledgeDocumentStatus;

import java.time.LocalDateTime;

public record KnowledgeDocumentResponse(
        String documentId, Long repositoryBindingId, String filename, String contentType,
        long sizeBytes, String sha256, String sourceType, String accessScope,
        KnowledgeDocumentStatus status, String failureCode, int chunkCount,
        long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, long version
) {
    public static KnowledgeDocumentResponse from(KnowledgeDocumentView view) {
        return new KnowledgeDocumentResponse(view.documentId(), view.repositoryBindingId(), view.filename(),
                view.contentType(), view.sizeBytes(), view.sha256(), view.sourceType(), view.accessScope(),
                view.status(), view.failureCode(), view.chunkCount(), view.createdBy(), view.createdAt(),
                view.updatedAt(), view.version());
    }
}
