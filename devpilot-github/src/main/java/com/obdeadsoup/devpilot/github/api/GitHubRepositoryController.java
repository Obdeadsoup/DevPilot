package com.obdeadsoup.devpilot.github.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.github.api.dto.BindGitHubRepositoryRequest;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryVersionRequest;
import com.obdeadsoup.devpilot.github.api.dto.GitHubBranchResponse;
import com.obdeadsoup.devpilot.github.application.GitHubRepositoryBindingService;
import com.obdeadsoup.devpilot.github.domain.GitHubRepositoryStatus;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories")
public class GitHubRepositoryController {

    private final GitHubRepositoryBindingService bindingService;

    public GitHubRepositoryController(GitHubRepositoryBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GitHubRepositoryResponse>> bind(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody BindGitHubRepositoryRequest request
    ) {
        GitHubRepositoryResponse binding = bindingService.bindRepository(
                workspaceId,
                projectId,
                request.owner(),
                request.repositoryName(),
                request.apiCredentialRef(),
                request.webhookSecretRef()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(binding));
    }

    @GetMapping
    public ApiResponse<PageResponse<GitHubRepositoryResponse>> list(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) GitHubRepositoryStatus status
    ) {
        return ApiResponse.success(bindingService.listRepositories(
                workspaceId, projectId, page, size, status
        ));
    }

    @GetMapping("/{bindingId}")
    public ApiResponse<GitHubRepositoryResponse> get(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId
    ) {
        return ApiResponse.success(bindingService.getRepository(workspaceId, projectId, bindingId));
    }

    @GetMapping("/{bindingId}/branches")
    public ApiResponse<List<GitHubBranchResponse>> listBranches(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId) {
        return ApiResponse.success(bindingService.listBranches(workspaceId, projectId, bindingId)
                .stream().map(GitHubBranchResponse::from).toList());
    }

    @PostMapping("/{bindingId}/disable")
    public ApiResponse<GitHubRepositoryResponse> disable(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId,
            @Valid @RequestBody GitHubRepositoryVersionRequest request
    ) {
        return ApiResponse.success(bindingService.disableRepository(
                workspaceId, projectId, bindingId, request.expectedVersion()
        ));
    }

    @PostMapping("/{bindingId}/reactivate")
    public ApiResponse<GitHubRepositoryResponse> reactivate(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId,
            @Valid @RequestBody GitHubRepositoryVersionRequest request
    ) {
        return ApiResponse.success(bindingService.reactivateRepository(
                workspaceId, projectId, bindingId, request.expectedVersion()
        ));
    }

    @PostMapping("/{bindingId}/refresh")
    public ApiResponse<GitHubRepositoryResponse> refresh(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId,
            @Valid @RequestBody GitHubRepositoryVersionRequest request
    ) {
        return ApiResponse.success(bindingService.refreshRepositoryMetadata(
                workspaceId, projectId, bindingId, request.expectedVersion()
        ));
    }

    @PostMapping("/{bindingId}/unbind")
    public ApiResponse<Void> unbind(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId,
            @Valid @RequestBody GitHubRepositoryVersionRequest request
    ) {
        bindingService.unbindRepository(
                workspaceId, projectId, bindingId, request.expectedVersion()
        );
        return ApiResponse.success(null);
    }
}
