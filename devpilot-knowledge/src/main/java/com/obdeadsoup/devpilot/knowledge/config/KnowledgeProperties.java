package com.obdeadsoup.devpilot.knowledge.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "devpilot.knowledge")
public record KnowledgeProperties(
        @Min(1_024) @Max(20 * 1024 * 1024) int maxUploadBytes,
        @Min(200) @Max(4_000) int chunkSize,
        @Min(0) @Max(1_000) int chunkOverlap,
        @Min(1) @Max(10_000) int maxChunksPerDocument,
        @Min(32) @Max(2_048) int embeddingDimensions,
        @Min(10) @Max(10_000) int maxSearchableChunks,
        @Min(1) @Max(100) int denseCandidateLimit,
        @Min(1) @Max(100) int sparseCandidateLimit,
        @Min(1) @Max(20) int defaultTopK,
        @Min(1) @Max(8) int workerThreads,
        @Min(1) @Max(1_000) int workerQueueCapacity,
        @NotBlank String embeddingMode,
        @NotBlank String embeddingEndpoint,
        @NotBlank String rerankerMode,
        @NotBlank String rerankerEndpoint,
        @NotBlank String storageMode,
        @NotBlank String localDirectory,
        @NotBlank String minioEndpoint,
        @NotBlank String minioAccessKey,
        @NotBlank String minioSecretKey,
        @NotBlank String minioBucket
) {
    @AssertTrue(message = "chunk overlap must be smaller than chunk size")
    public boolean isChunkOverlapValid() {
        return chunkOverlap < chunkSize;
    }

    @AssertTrue(message = "storage mode must be local or minio")
    public boolean isStorageModeValid() {
        return "local".equalsIgnoreCase(storageMode) || "minio".equalsIgnoreCase(storageMode);
    }

    @AssertTrue(message = "embedding mode must be local or tei")
    public boolean isEmbeddingModeValid() {
        return "local".equalsIgnoreCase(embeddingMode) || "tei".equalsIgnoreCase(embeddingMode);
    }

    @AssertTrue(message = "reranker mode must be local or tei")
    public boolean isRerankerModeValid() {
        return "local".equalsIgnoreCase(rerankerMode) || "tei".equalsIgnoreCase(rerankerMode);
    }
}
