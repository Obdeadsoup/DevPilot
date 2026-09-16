package com.obdeadsoup.devpilot.knowledge.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import com.obdeadsoup.devpilot.knowledge.domain.KnowledgeAccessScope;
import com.obdeadsoup.devpilot.knowledge.domain.KnowledgeDocumentStatus;
import com.obdeadsoup.devpilot.knowledge.error.KnowledgeErrorCode;
import com.obdeadsoup.devpilot.knowledge.ingestion.KnowledgeDocumentParser;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import com.obdeadsoup.devpilot.knowledge.storage.KnowledgeObjectStorage;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {
    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService authorizationService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeObjectStorage objectStorage;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeProperties properties;
    private final ApplicationEventPublisher events;

    public KnowledgeDocumentService(CurrentUserProvider currentUserProvider,
                                    ProjectAuthorizationService authorizationService,
                                    KnowledgeDocumentMapper documentMapper,
                                    KnowledgeObjectStorage objectStorage,
                                    KnowledgeDocumentParser parser,
                                    KnowledgeProperties properties,
                                    ApplicationEventPublisher events) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.documentMapper = documentMapper;
        this.objectStorage = objectStorage;
        this.parser = parser;
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public KnowledgeDocumentView upload(long workspaceId, long projectId,
                                        String originalFilename, String contentType, byte[] content) {
        long actor = currentUserProvider.requireUserId();
        authorizationService.requirePermission(actor, workspaceId, projectId, ProjectPermission.KNOWLEDGE_MANAGE);
        String filename = safeFilename(originalFilename);
        if (content == null || content.length == 0 || !parser.supports(filename)) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
        if (content.length > properties.maxUploadBytes()) {
            throw new BusinessException(KnowledgeErrorCode.DOCUMENT_TOO_LARGE);
        }

        String documentId = UUID.randomUUID().toString();
        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        String objectKey = "workspaces/" + workspaceId + "/projects/" + projectId + "/" + documentId + extension;
        String mediaType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        objectStorage.put(objectKey, content, mediaType);
        try {
            KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
            document.setDocumentId(documentId);
            document.setWorkspaceId(workspaceId);
            document.setProjectId(projectId);
            // Uploads belong to the Project scope. Repository linkage is only set by a future trusted Git sync adapter.
            document.setRepositoryBindingId(null);
            document.setFilename(filename);
            document.setContentType(mediaType);
            document.setSizeBytes(content.length);
            document.setSha256(sha256(content));
            document.setObjectKey(objectKey);
            document.setSourceType("UPLOAD");
            document.setAccessScope(KnowledgeAccessScope.PROJECT_MEMBER.name());
            document.setStatus(KnowledgeDocumentStatus.UPLOADED.name());
            document.setCreatedBy(actor);
            documentMapper.insert(document);
            events.publishEvent(new KnowledgeDocumentUploadedEvent(document.getId()));
            return KnowledgeDocumentView.from(documentMapper.findById(document.getId()).orElseThrow());
        } catch (RuntimeException exception) {
            objectStorage.delete(objectKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> list(long workspaceId, long projectId) {
        long actor = currentUserProvider.requireUserId();
        authorizationService.requirePermission(actor, workspaceId, projectId, ProjectPermission.KNOWLEDGE_READ);
        return documentMapper.findByProject(workspaceId, projectId).stream().map(KnowledgeDocumentView::from).toList();
    }

    @Transactional
    public KnowledgeDocumentView retry(long workspaceId, long projectId, String documentId, long expectedVersion) {
        long actor = currentUserProvider.requireUserId();
        authorizationService.requirePermission(actor, workspaceId, projectId, ProjectPermission.KNOWLEDGE_MANAGE);
        KnowledgeDocumentEntity document = documentMapper.findByScope(workspaceId, projectId, documentId)
                .orElseThrow(() -> new BusinessException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND));
        if (!KnowledgeDocumentStatus.FAILED.name().equals(document.getStatus())
                || documentMapper.resetForRetry(document.getId(), expectedVersion) != 1) {
            throw new BusinessException(KnowledgeErrorCode.INGESTION_CONFLICT);
        }
        events.publishEvent(new KnowledgeDocumentUploadedEvent(document.getId()));
        return KnowledgeDocumentView.from(documentMapper.findById(document.getId()).orElseThrow());
    }

    private String safeFilename(String original) {
        if (original == null || original.isBlank()) throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        final String filename;
        try {
            filename = Path.of(original).getFileName().toString().strip();
        } catch (InvalidPathException exception) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
        if (filename.isBlank() || filename.length() > 255) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
        return filename;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
