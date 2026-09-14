package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "reranker-mode", havingValue = "tei")
public final class TeiKnowledgeReranker implements KnowledgeReranker {
    private final RestClient client;

    public TeiKnowledgeReranker(KnowledgeProperties properties) {
        this.client = RestClient.builder().baseUrl(properties.rerankerEndpoint()).build();
    }

    @Override
    public List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        JsonNode response = client.post().uri("/rerank")
                .body(Map.of("query", query,
                        "texts", candidates.stream().map(KnowledgeSearchHit::content).toList(),
                        "raw_scores", false))
                .retrieve().body(JsonNode.class);
        JsonNode results = response != null && response.isArray()
                ? response : response == null ? null : response.get("results");
        if (results == null || !results.isArray()) {
            throw new IllegalStateException("TEI reranker response is invalid");
        }
        List<KnowledgeSearchHit> ranked = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < candidates.size()) {
                ranked.add(withScore(candidates.get(index), result.path("score").asDouble()));
            }
        }
        if (ranked.isEmpty()) {
            throw new IllegalStateException("TEI reranker response contains no valid candidates");
        }
        return ranked.stream().sorted(Comparator.comparingDouble(KnowledgeSearchHit::rerankScore).reversed())
                .limit(topK).toList();
    }

    private KnowledgeSearchHit withScore(KnowledgeSearchHit hit, double score) {
        return new KnowledgeSearchHit(hit.chunkId(), hit.documentId(), hit.sourceFile(), hit.sourceType(),
                hit.repositoryBindingId(), hit.commitSha(), hit.chunkIndex(), hit.content(), hit.denseScore(),
                hit.sparseScore(), hit.fusionScore(), score);
    }
}
