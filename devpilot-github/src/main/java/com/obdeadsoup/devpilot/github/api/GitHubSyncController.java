package com.obdeadsoup.devpilot.github.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSyncRunReceiptResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSyncRunResponse;
import com.obdeadsoup.devpilot.github.application.GitHubSyncRunService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}")
public class GitHubSyncController {

    private final GitHubSyncRunService syncRunService;

    public GitHubSyncController(GitHubSyncRunService syncRunService) {
        this.syncRunService = syncRunService;
    }

    @PostMapping("/sync/commits")
    public ResponseEntity<ApiResponse<GitHubSyncRunReceiptResponse>> syncCommits(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                syncRunService.requestManualCommitSync(workspaceId, projectId, bindingId)
        ));
    }

    @GetMapping("/sync-runs/{runId}")
    public ApiResponse<GitHubSyncRunResponse> getRun(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long bindingId,
            @PathVariable @Positive long runId
    ) {
        return ApiResponse.success(syncRunService.getRun(workspaceId, projectId, bindingId, runId));
    }
}
