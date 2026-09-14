package com.obdeadsoup.devpilot.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalKnowledgeRerankerTest {
    @Test
    void exactTechnicalTermsImproveOfflineFallbackRanking() {
        KnowledgeSearchHit generic = hit("generic", "The system retries background work.");
        KnowledgeSearchHit exact = hit("exact", "AgentRun TIMEOUT is retried by the Outbox worker.");

        List<KnowledgeSearchHit> result = new LocalKnowledgeReranker()
                .rerank("AgentRun TIMEOUT Outbox", List.of(generic, exact), 1);

        assertThat(result).extracting(KnowledgeSearchHit::chunkId).containsExactly("exact");
    }

    private KnowledgeSearchHit hit(String id, String content) {
        return new KnowledgeSearchHit(id, "document", "architecture.md", "MARKDOWN", null, null,
                0, content, 0.5, 0.5, 0.5, 0.5);
    }
}
