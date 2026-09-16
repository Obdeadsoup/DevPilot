package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "embedding-mode", havingValue = "tei")
public final class TeiKnowledgeEmbeddingService implements KnowledgeEmbeddingService {
    private final RestClient client;

    public TeiKnowledgeEmbeddingService(KnowledgeProperties properties) {
        this.client = RestClient.builder().baseUrl(properties.embeddingEndpoint()).build();
    }

    @Override
    public double[] embed(String text) {
        JsonNode response = client.post().uri("/embed").body(Map.of("inputs", text))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.isArray() || response.isEmpty()) {
            throw new IllegalStateException("TEI embedding response is empty");
        }
        JsonNode vector = response.get(0).isArray() ? response.get(0) : response;
        double[] result = new double[vector.size()];
        for (int index = 0; index < vector.size(); index++) result[index] = vector.get(index).asDouble();
        return result;
    }
}
