package com.obdeadsoup.devpilot.knowledge.persistence.entity;

public record KnowledgeChunkEntity(
        long id,
        String chunkId,
        long documentId,
        long workspaceId,
        long projectId,
        Long repositoryBindingId,
        String sourceFile,
        String sourceType,
        String commitSha,
        long documentVersion,
        int chunkIndex,
        String chunkText,
        int tokenCount,
        String embeddingJson,
        String accessScope
) {
}
