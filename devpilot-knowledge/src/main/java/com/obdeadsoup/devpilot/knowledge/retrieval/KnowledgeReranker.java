package com.obdeadsoup.devpilot.knowledge.retrieval;

import java.util.List;

public interface KnowledgeReranker {
    List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates, int topK);
}
