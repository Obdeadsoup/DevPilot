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
    // TEI rejects requests with more than 32 texts; smaller batches also bound CPU memory use.
    private static final int MAX_TEXTS_PER_REQUEST = 16;
    private final RestClient client;

    public TeiKnowledgeReranker(KnowledgeProperties properties) {
        this.client = RestClient.builder().baseUrl(properties.rerankerEndpoint()).build();
    }

    @Override
    public List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        List<KnowledgeSearchHit> ranked = new ArrayList<>();
        for (int offset = 0; offset < candidates.size(); offset += MAX_TEXTS_PER_REQUEST) {
            List<KnowledgeSearchHit> batch = candidates.subList(offset,
                    Math.min(offset + MAX_TEXTS_PER_REQUEST, candidates.size()));
            JsonNode response = client.post().uri("/rerank")
                    .body(Map.of("query", query,
                            "texts", batch.stream().map(KnowledgeSearchHit::content).toList(),
                            "raw_scores", false))
                    .retrieve().body(JsonNode.class);
            JsonNode results = response != null && response.isArray()
                    ? response : response == null ? null : response.get("results");
            if (results == null || !results.isArray()) {
                throw new IllegalStateException("TEI reranker response is invalid");
            }
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index >= 0 && index < batch.size()) {
                    ranked.add(withScore(batch.get(index), result.path("score").asDouble()));
                }
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
