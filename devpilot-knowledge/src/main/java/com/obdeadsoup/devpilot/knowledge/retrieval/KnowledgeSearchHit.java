package com.obdeadsoup.devpilot.knowledge.retrieval;

public record KnowledgeSearchHit(
        String chunkId,
        String documentId,
        String sourceFile,
        String sourceType,
        Long repositoryBindingId,
        String commitSha,
        int chunkIndex,
        String content,
        double denseScore,
        double sparseScore,
        double fusionScore,
        double rerankScore
) {
}
