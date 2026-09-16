package com.obdeadsoup.devpilot.knowledge.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic offline fallback. Full Compose uses the TEI cross-encoder implementation. */
@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "reranker-mode", havingValue = "local", matchIfMissing = true)
public final class LocalKnowledgeReranker implements KnowledgeReranker {
    @Override
    public List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates, int topK) {
        Set<String> queryTerms = new HashSet<>(KnowledgeText.tokenize(query));
        return candidates.stream()
                .map(hit -> withScore(hit, 0.9 * hit.rerankScore() + 0.1 * coverage(queryTerms, hit.content())))
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::rerankScore).reversed()
                        .thenComparing(KnowledgeSearchHit::chunkId))
                .limit(topK)
                .toList();
    }

    private double coverage(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty()) return 0;
        Set<String> terms = new HashSet<>(KnowledgeText.tokenize(text));
        return (double) queryTerms.stream().filter(terms::contains).count() / queryTerms.size();
    }

    private KnowledgeSearchHit withScore(KnowledgeSearchHit hit, double score) {
        return new KnowledgeSearchHit(hit.chunkId(), hit.documentId(), hit.sourceFile(), hit.sourceType(),
                hit.repositoryBindingId(), hit.commitSha(), hit.chunkIndex(), hit.content(), hit.denseScore(),
                hit.sparseScore(), hit.fusionScore(), score);
    }
}
