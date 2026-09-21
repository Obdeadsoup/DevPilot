package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeEmbeddingService;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeText;
import com.obdeadsoup.devpilot.knowledge.storage.KnowledgeObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private final KnowledgeIngestionPersistence persistence;
    private final KnowledgeObjectStorage storage;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeChunker chunker;
    private final KnowledgeEmbeddingService embeddings;
    private final ObjectMapper objectMapper;

    public KnowledgeIngestionService(KnowledgeIngestionPersistence persistence,
                                     KnowledgeObjectStorage storage,
                                     KnowledgeDocumentParser parser,
                                     KnowledgeChunker chunker,
                                     KnowledgeEmbeddingService embeddings,
                                     ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.objectMapper = objectMapper;
    }

    public void ingest(long databaseId) {
        long started = System.nanoTime();
        String stage = "BEGIN";
        KnowledgeDocumentEntity document = null;
        try {
            document = persistence.begin(databaseId).orElse(null);
            if (document == null) return;
            stage = "STORAGE_GET";
            byte[] content = storage.get(document.getObjectKey());
            stage = "PARSE";
            String text = parser.parse(document.getFilename(), content);
            stage = "CHUNK";
            List<String> texts = chunker.chunk(text);
            if (texts.isEmpty()) throw new IllegalArgumentException("document produced no chunks");
            List<KnowledgeChunkEntity> chunks = new ArrayList<>(texts.size());
            stage = "EMBED";
            for (int index = 0; index < texts.size(); index++) {
                String chunkText = texts.get(index);
                chunks.add(new KnowledgeChunkEntity(0, document.getDocumentId() + ":" + document.getVersion() + ":" + index,
                        document.getId(), document.getWorkspaceId(), document.getProjectId(),
                        document.getRepositoryBindingId(), document.getFilename(), document.getSourceType(), null,
                        document.getVersion(), index, chunkText, Math.max(1, KnowledgeText.tokenize(chunkText).size()),
                        vectorJson(embeddings.embed(chunkText)), document.getAccessScope()));
            }
            stage = "PERSIST";
            persistence.complete(databaseId, chunks);
            log.info("Knowledge ingestion completed documentId={} databaseId={} chunks={} elapsedMs={}",
                    document.getDocumentId(), databaseId, chunks.size(), elapsedMs(started));
        } catch (RuntimeException exception) {
            log.warn("Knowledge ingestion failed documentId={} databaseId={} stage={} failureCode={} exceptionType={} elapsedMs={}",
                    document == null ? "unknown" : document.getDocumentId(), databaseId, stage,
                    stableFailureCode(exception), exception.getClass().getSimpleName(), elapsedMs(started));
            persistence.fail(databaseId, stableFailureCode(exception));
        }
    }

    private long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String vectorJson(double[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String stableFailureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toUpperCase();
        return name.length() <= 64 ? name : "INGESTION_ERROR";
    }
}
