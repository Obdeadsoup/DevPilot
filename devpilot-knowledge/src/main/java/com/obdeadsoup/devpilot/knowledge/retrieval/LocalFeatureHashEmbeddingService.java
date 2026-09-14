package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Offline-safe dense baseline; the port can be replaced by TEI without changing ingestion/retrieval. */
@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "embedding-mode", havingValue = "local", matchIfMissing = true)
public final class LocalFeatureHashEmbeddingService implements KnowledgeEmbeddingService {
    private final int dimensions;

    public LocalFeatureHashEmbeddingService(KnowledgeProperties properties) {
        this.dimensions = properties.embeddingDimensions();
    }

    @Override
    public double[] embed(String text) {
        double[] vector = new double[dimensions];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : KnowledgeText.tokenize(normalized)) {
            add(vector, token, 1.0);
            if (token.length() >= 3) {
                for (int index = 0; index <= token.length() - 3; index++) {
                    add(vector, token.substring(index, index + 3), 0.35);
                }
            }
        }
        double norm = 0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int index = 0; index < vector.length; index++) vector[index] /= norm;
        return vector;
    }

    private void add(double[] vector, String feature, double weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        vector[index] += (hash & 1) == 0 ? weight : -weight;
    }
}
