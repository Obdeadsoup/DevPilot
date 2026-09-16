package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeEmbeddingService;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeText;
import com.obdeadsoup.devpilot.knowledge.storage.KnowledgeObjectStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeIngestionService {
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
        KnowledgeDocumentEntity document = persistence.begin(databaseId).orElse(null);
        if (document == null) return;
        try {
            String text = parser.parse(document.getFilename(), storage.get(document.getObjectKey()));
            List<String> texts = chunker.chunk(text);
            if (texts.isEmpty()) throw new IllegalArgumentException("document produced no chunks");
            List<KnowledgeChunkEntity> chunks = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                String chunkText = texts.get(index);
                chunks.add(new KnowledgeChunkEntity(0, document.getDocumentId() + ":" + document.getVersion() + ":" + index,
                        document.getId(), document.getWorkspaceId(), document.getProjectId(),
                        document.getRepositoryBindingId(), document.getFilename(), document.getSourceType(), null,
                        document.getVersion(), index, chunkText, Math.max(1, KnowledgeText.tokenize(chunkText).size()),
                        vectorJson(embeddings.embed(chunkText)), document.getAccessScope()));
            }
            persistence.complete(databaseId, chunks);
        } catch (RuntimeException exception) {
            persistence.fail(databaseId, stableFailureCode(exception));
        }
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
