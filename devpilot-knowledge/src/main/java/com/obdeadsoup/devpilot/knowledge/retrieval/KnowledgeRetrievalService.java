package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import com.obdeadsoup.devpilot.knowledge.error.KnowledgeErrorCode;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeChunkMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeQueryTraceMapper;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

@Service
public class KnowledgeRetrievalService {
    private static final double RRF_K = 60.0;
    private final ProjectAuthorizationService authorizationService;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeQueryTraceMapper traceMapper;
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeReranker reranker;
    private final ConversationAwareQueryRewriter rewriter;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeRetrievalService(ProjectAuthorizationService authorizationService,
                                     KnowledgeChunkMapper chunkMapper,
                                     KnowledgeDocumentMapper documentMapper,
                                     KnowledgeQueryTraceMapper traceMapper,
                                     KnowledgeEmbeddingService embeddings,
                                     KnowledgeReranker reranker,
                                     ConversationAwareQueryRewriter rewriter,
                                     KnowledgeProperties properties,
                                     ObjectMapper objectMapper) {
        this.authorizationService = authorizationService;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.traceMapper = traceMapper;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.rewriter = rewriter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KnowledgeRetrievalResult searchForActor(long actorUserId, long workspaceId, long projectId,
                                                   String query, List<String> conversationHistory, Integer requestedTopK) {
        authorizationService.requirePermission(actorUserId, workspaceId, projectId, ProjectPermission.KNOWLEDGE_READ);
        String original = normalizeQuery(query);
        List<String> history = normalizeHistory(conversationHistory);
        int topK = requestedTopK == null ? properties.defaultTopK() : requestedTopK;
        if (topK < 1 || topK > 20) throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        String rewritten = rewriter.rewrite(original, history);

        // Authorization predicate pushdown: no cross-project candidate enters either scoring branch.
        List<KnowledgeChunkEntity> chunks = chunkMapper.findRetrievableByProject(
                workspaceId, projectId, properties.maxSearchableChunks());
        long version = documentMapper.knowledgeVersion(workspaceId, projectId);
        List<KnowledgeSearchHit> hits = rank(rewritten, chunks, topK);
        traceMapper.insert(workspaceId, projectId, actorUserId, original, rewritten,
                json(hits.stream().map(KnowledgeSearchHit::chunkId).toList()), version);
        return new KnowledgeRetrievalResult(original, rewritten, version, hits);
    }

    private List<KnowledgeSearchHit> rank(String query, List<KnowledgeChunkEntity> chunks, int topK) {
        if (chunks.isEmpty()) return List.of();
        double[] queryVector = embeddings.embed(query);
        Map<Long, Double> dense = new HashMap<>();
        for (KnowledgeChunkEntity chunk : chunks) dense.put(chunk.id(), cosine(queryVector, vector(chunk.embeddingJson())));
        Map<Long, Double> sparse = bm25(query, chunks);

        List<KnowledgeChunkEntity> denseRank = top(chunks, chunk -> dense.get(chunk.id()),
                properties.denseCandidateLimit());
        List<KnowledgeChunkEntity> sparseRank = top(chunks, chunk -> sparse.get(chunk.id()),
                properties.sparseCandidateLimit());
        Map<Long, Double> fusion = new HashMap<>();
        addRrf(fusion, denseRank);
        addRrf(fusion, sparseRank);

        Map<Long, KnowledgeChunkEntity> candidates = new LinkedHashMap<>();
        denseRank.forEach(chunk -> candidates.put(chunk.id(), chunk));
        sparseRank.forEach(chunk -> candidates.put(chunk.id(), chunk));
        double maxDense = max(dense.values());
        double maxSparse = max(sparse.values());
        double maxFusion = max(fusion.values());
        List<KnowledgeSearchHit> fused = candidates.values().stream()
                .map(chunk -> {
                    double denseScore = dense.getOrDefault(chunk.id(), 0.0);
                    double sparseScore = sparse.getOrDefault(chunk.id(), 0.0);
                    double fusionScore = fusion.getOrDefault(chunk.id(), 0.0);
                    double preliminaryScore = 0.45 * normalized(fusionScore, maxFusion)
                            + 0.25 * normalized(denseScore, maxDense)
                            + 0.30 * normalized(sparseScore, maxSparse);
                    return new KnowledgeSearchHit(chunk.chunkId(), documentPublicId(chunk.chunkId()),
                            chunk.sourceFile(), chunk.sourceType(), chunk.repositoryBindingId(), chunk.commitSha(),
                            chunk.chunkIndex(), chunk.chunkText(), denseScore, sparseScore, fusionScore, preliminaryScore);
                })
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::rerankScore).reversed()
                        .thenComparing(KnowledgeSearchHit::chunkId))
                .toList();
        return reranker.rerank(query, fused, topK);
    }

    private Map<Long, Double> bm25(String query, List<KnowledgeChunkEntity> chunks) {
        List<String> queryTerms = KnowledgeText.tokenize(query);
        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<Long, List<String>> documentTerms = new HashMap<>();
        double averageLength = 0;
        for (KnowledgeChunkEntity chunk : chunks) {
            List<String> terms = KnowledgeText.tokenize(chunk.chunkText());
            documentTerms.put(chunk.id(), terms);
            averageLength += terms.size();
            new HashSet<>(terms).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        averageLength = Math.max(1, averageLength / chunks.size());
        Map<Long, Double> scores = new HashMap<>();
        for (KnowledgeChunkEntity chunk : chunks) {
            List<String> terms = documentTerms.get(chunk.id());
            Map<String, Integer> tf = new HashMap<>();
            terms.forEach(term -> tf.merge(term, 1, Integer::sum));
            double score = 0;
            for (String term : queryTerms) {
                int frequency = tf.getOrDefault(term, 0);
                if (frequency == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                double denominator = frequency + 1.2 * (1 - 0.75 + 0.75 * terms.size() / averageLength);
                score += idf * frequency * 2.2 / denominator;
            }
            scores.put(chunk.id(), score);
        }
        return scores;
    }

    private List<KnowledgeChunkEntity> top(List<KnowledgeChunkEntity> chunks,
                                           ToDoubleFunction<KnowledgeChunkEntity> score, int limit) {
        return chunks.stream().filter(chunk -> score.applyAsDouble(chunk) > 0)
                .sorted(Comparator.comparingDouble(score).reversed().thenComparing(KnowledgeChunkEntity::chunkId))
                .limit(limit).toList();
    }

    private void addRrf(Map<Long, Double> scores, List<KnowledgeChunkEntity> ranking) {
        for (int index = 0; index < ranking.size(); index++) {
            scores.merge(ranking.get(index).id(), 1.0 / (RRF_K + index + 1), Double::sum);
        }
    }

    private double cosine(double[] left, double[] right) {
        if (left.length != right.length) return 0;
        double score = 0;
        for (int index = 0; index < left.length; index++) score += left[index] * right[index];
        return Math.max(0, score);
    }

    private double[] vector(String json) {
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (JsonProcessingException exception) {
            return new double[0];
        }
    }

    private double max(Iterable<Double> values) {
        double max = 0;
        for (double value : values) max = Math.max(max, value);
        return max;
    }

    private double normalized(double value, double max) { return max <= 0 ? 0 : value / max; }

    private String documentPublicId(String chunkId) {
        int separator = chunkId.indexOf(':');
        return separator < 0 ? chunkId : chunkId.substring(0, separator);
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank() || query.strip().length() > 2_000) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
        return query.strip();
    }

    private List<String> normalizeHistory(List<String> history) {
        if (history == null) return List.of();
        if (history.size() > 6) throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        List<String> result = new ArrayList<>();
        for (String item : history) {
            if (item != null && !item.isBlank()) result.add(item.strip().substring(0, Math.min(2_000, item.strip().length())));
        }
        return List.copyOf(result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
