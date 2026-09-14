package com.obdeadsoup.devpilot.knowledge.application;

import com.obdeadsoup.devpilot.knowledge.domain.KnowledgeDocumentStatus;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;

import java.time.LocalDateTime;

public record KnowledgeDocumentView(
        String documentId,
        long workspaceId,
        long projectId,
        Long repositoryBindingId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String sourceType,
        String accessScope,
        KnowledgeDocumentStatus status,
        String failureCode,
        int chunkCount,
        long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
    public static KnowledgeDocumentView from(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentView(entity.getDocumentId(), entity.getWorkspaceId(), entity.getProjectId(),
                entity.getRepositoryBindingId(), entity.getFilename(), entity.getContentType(), entity.getSizeBytes(),
                entity.getSha256(), entity.getSourceType(), entity.getAccessScope(),
                KnowledgeDocumentStatus.valueOf(entity.getStatus()), entity.getFailureCode(), entity.getChunkCount(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }
}
