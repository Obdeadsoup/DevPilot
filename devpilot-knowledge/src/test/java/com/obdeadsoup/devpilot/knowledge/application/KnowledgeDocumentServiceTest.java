package com.obdeadsoup.devpilot.knowledge.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import com.obdeadsoup.devpilot.knowledge.domain.KnowledgeDocumentStatus;
import com.obdeadsoup.devpilot.knowledge.ingestion.KnowledgeDocumentParser;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.obdeadsoup.devpilot.knowledge.storage.KnowledgeObjectStorage;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceTest {
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ProjectAuthorizationService authorization = mock(ProjectAuthorizationService.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final KnowledgeDocumentService service = new KnowledgeDocumentService(currentUser, authorization,
            documents, mock(KnowledgeObjectStorage.class), new KnowledgeDocumentParser(),
            mock(KnowledgeProperties.class), events);

    @Test
    void retryResetsFailedDocumentUsingExpectedVersionAndPublishesIngestionEvent() {
        when(currentUser.requireUserId()).thenReturn(7L);
        when(documents.findByScope(1, 4, "doc-1")).thenReturn(Optional.of(document("FAILED", 5)));
        when(documents.resetForRetry(42, 5)).thenReturn(1);
        when(documents.findById(42)).thenReturn(Optional.of(document("UPLOADED", 6)));

        KnowledgeDocumentView retried = service.retry(1, 4, "doc-1", 5);

        verify(authorization).requirePermission(7, 1, 4, ProjectPermission.KNOWLEDGE_MANAGE);
        verify(events).publishEvent(new KnowledgeDocumentUploadedEvent(42));
        assertThat(retried.status()).isEqualTo(KnowledgeDocumentStatus.UPLOADED);
        assertThat(retried.version()).isEqualTo(6);
    }

    @Test
    void staleRetryDoesNotPublishAnotherIngestionEvent() {
        when(currentUser.requireUserId()).thenReturn(7L);
        when(documents.findByScope(1, 4, "doc-1")).thenReturn(Optional.of(document("FAILED", 5)));
        when(documents.resetForRetry(42, 4)).thenReturn(0);

        assertThatThrownBy(() -> service.retry(1, 4, "doc-1", 4))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(events);
    }

    private KnowledgeDocumentEntity document(String status, long version) {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(42L);
        document.setDocumentId("doc-1");
        document.setWorkspaceId(1);
        document.setProjectId(4);
        document.setFilename("README.md");
        document.setStatus(status);
        document.setVersion(version);
        return document;
    }
}
