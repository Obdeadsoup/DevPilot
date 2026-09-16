package com.obdeadsoup.devpilot.knowledge.retrieval;

import java.util.List;

public record KnowledgeRetrievalResult(
        String originalQuery,
        String rewrittenQuery,
        long knowledgeVersion,
        List<KnowledgeSearchHit> hits
) {
    public KnowledgeRetrievalResult {
        hits = List.copyOf(hits);
    }
}
