package com.obdeadsoup.devpilot.knowledge.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.knowledge.api.dto.KnowledgeDocumentResponse;
import com.obdeadsoup.devpilot.knowledge.api.dto.KnowledgeRetryRequest;
import com.obdeadsoup.devpilot.knowledge.api.dto.KnowledgeSearchRequest;
import com.obdeadsoup.devpilot.knowledge.application.KnowledgeDocumentService;
import com.obdeadsoup.devpilot.knowledge.error.KnowledgeErrorCode;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeRetrievalResult;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeRetrievalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/knowledge")
public class KnowledgeController {
    private final KnowledgeDocumentService documents;
    private final KnowledgeRetrievalService retrieval;
    private final CurrentUserProvider currentUserProvider;

    public KnowledgeController(KnowledgeDocumentService documents,
                               KnowledgeRetrievalService retrieval,
                               CurrentUserProvider currentUserProvider) {
        this.documents = documents;
        this.retrieval = retrieval;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> upload(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @RequestPart("file") MultipartFile file) {
        try {
            return ResponseEntity.accepted().body(ApiResponse.success(KnowledgeDocumentResponse.from(
                    documents.upload(workspaceId, projectId, file.getOriginalFilename(),
                            file.getContentType(), file.getBytes()))));
        } catch (IOException exception) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
    }

    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> list(@PathVariable @Positive long workspaceId,
                                                              @PathVariable @Positive long projectId) {
        return ApiResponse.success(documents.list(workspaceId, projectId).stream()
                .map(KnowledgeDocumentResponse::from).toList());
    }

    @PostMapping("/documents/{documentId}/retry")
    public ApiResponse<KnowledgeDocumentResponse> retry(@PathVariable @Positive long workspaceId,
                                                         @PathVariable @Positive long projectId,
                                                         @PathVariable String documentId,
                                                         @Valid @RequestBody KnowledgeRetryRequest request) {
        return ApiResponse.success(KnowledgeDocumentResponse.from(
                documents.retry(workspaceId, projectId, documentId, request.expectedVersion())));
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeRetrievalResult> search(@PathVariable @Positive long workspaceId,
                                                         @PathVariable @Positive long projectId,
                                                         @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.success(retrieval.searchForActor(currentUserProvider.requireUserId(), workspaceId, projectId,
                request.query(), request.conversationHistory(), request.topK()));
    }
}
