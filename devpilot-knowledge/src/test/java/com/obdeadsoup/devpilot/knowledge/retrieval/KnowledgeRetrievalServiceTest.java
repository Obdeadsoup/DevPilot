package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeChunkMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeQueryTraceMapper;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {
    private final ProjectAuthorizationService authorization = mock(ProjectAuthorizationService.class);
    private final KnowledgeChunkMapper chunks = mock(KnowledgeChunkMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeQueryTraceMapper traces = mock(KnowledgeQueryTraceMapper.class);
    private final KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
    private final KnowledgeProperties properties = mock(KnowledgeProperties.class);
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        when(properties.defaultTopK()).thenReturn(8);
        when(properties.maxSearchableChunks()).thenReturn(2_000);
        when(properties.denseCandidateLimit()).thenReturn(50);
        when(properties.sparseCandidateLimit()).thenReturn(50);
        service = new KnowledgeRetrievalService(authorization, chunks, documents, traces, embeddings,
                new LocalKnowledgeReranker(), new ConversationAwareQueryRewriter(), properties, new ObjectMapper());
    }

    @Test
    void authorizesBeforeScopedCandidateRetrievalAndReturnsTraceableSource() {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity(10, "doc-public:1:0", 20, 100, 200,
                null, "architecture.md", "UPLOAD", null, 1, 0,
                "Outbox dispatcher retries TIMEOUT events with exponential backoff.", 8,
                "[1.0,0.0]", "PROJECT_MEMBER");
        when(chunks.findRetrievableByProject(100, 200, 2_000)).thenReturn(List.of(chunk));
        when(documents.knowledgeVersion(100, 200)).thenReturn(1_000_001L);
        when(embeddings.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(new double[]{1.0, 0.0});

        KnowledgeRetrievalResult result = service.searchForActor(7, 100, 200,
                "那失败后怎么办？", List.of("DevPilot 的 Outbox 如何投递？"), 5);

        verify(authorization).requirePermission(7, 100, 200, ProjectPermission.KNOWLEDGE_READ);
        verify(chunks).findRetrievableByProject(100, 200, 2_000);
        assertThat(result.rewrittenQuery()).startsWith("DevPilot 的 Outbox 如何投递？");
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.documentId()).isEqualTo("doc-public");
            assertThat(hit.sourceFile()).isEqualTo("architecture.md");
            assertThat(hit.content()).contains("exponential backoff");
        });
        verify(traces).insert(org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(200L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.contains("doc-public:1:0"),
                org.mockito.ArgumentMatchers.eq(1_000_001L));
    }

    @Test
    void deniedActorNeverReachesCandidateStore() {
        doThrow(new BusinessException(IdentityErrorCode.ACCESS_DENIED)).when(authorization)
                .requirePermission(9, 100, 200, ProjectPermission.KNOWLEDGE_READ);

        assertThatThrownBy(() -> service.searchForActor(9, 100, 200, "Outbox", List.of(), 5))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(chunks, documents, traces, embeddings);
    }
}
