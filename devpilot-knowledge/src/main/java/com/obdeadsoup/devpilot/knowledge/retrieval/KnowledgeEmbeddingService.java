package com.obdeadsoup.devpilot.knowledge.retrieval;

public interface KnowledgeEmbeddingService {
    double[] embed(String text);
}
