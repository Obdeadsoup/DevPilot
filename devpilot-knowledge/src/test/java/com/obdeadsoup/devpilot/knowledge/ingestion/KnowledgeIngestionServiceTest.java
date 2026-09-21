package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeEmbeddingService;
import com.obdeadsoup.devpilot.knowledge.storage.KnowledgeObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KnowledgeIngestionServiceTest {
    private final KnowledgeIngestionPersistence persistence = mock(KnowledgeIngestionPersistence.class);
    private final KnowledgeObjectStorage storage = mock(KnowledgeObjectStorage.class);
    private final KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
    private final KnowledgeIngestionService service = new KnowledgeIngestionService(
            persistence, storage, new KnowledgeDocumentParser(), new KnowledgeChunker(100, 10, 10),
            embeddings, new ObjectMapper());

    @Test
    void successfulMarkdownPersistsChunksAndReadyTransition() {
        KnowledgeDocumentEntity document = document();
        when(persistence.begin(1)).thenReturn(Optional.of(document));
        when(storage.get("object-key")).thenReturn("DevPilot knowledge search works.".getBytes(StandardCharsets.UTF_8));
        when(embeddings.embed(any())).thenReturn(new double[] {0.25, 0.75});

        service.ingest(1);

        @SuppressWarnings("unchecked")
        var chunks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(persistence).complete(eq(1L), chunks.capture());
        assertThat((List<KnowledgeChunkEntity>) chunks.getValue()).hasSize(1);
        verify(persistence, never()).fail(anyLong(), any());
    }

    @Test
    void embeddingFailurePersistsStableSafeFailureCode() {
        when(persistence.begin(1)).thenReturn(Optional.of(document()));
        when(storage.get("object-key")).thenReturn("DevPilot knowledge search works.".getBytes(StandardCharsets.UTF_8));
        when(embeddings.embed(any())).thenThrow(new ResourceAccessException("sensitive endpoint"));

        service.ingest(1);

        verify(persistence).fail(1, "RESOURCEACCESSEXCEPTION");
        verify(persistence, never()).complete(anyLong(), any());
    }

    private KnowledgeDocumentEntity document() {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(1L);
        document.setDocumentId("document-1");
        document.setWorkspaceId(1);
        document.setProjectId(4);
        document.setFilename("README.md");
        document.setObjectKey("object-key");
        document.setSourceType("UPLOAD");
        document.setAccessScope("PROJECT");
        document.setVersion(1);
        return document;
    }
}
