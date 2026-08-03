package com.obdeadsoup.devpilot.github.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubIssueResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubPullRequestResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubPullRequestReviewResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSnapshotPageResponse;
import com.obdeadsoup.devpilot.github.application.GitHubSnapshotQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/github")
public class GitHubSnapshotController {

    private final GitHubSnapshotQueryService service;

    public GitHubSnapshotController(GitHubSnapshotQueryService service) {
        this.service = service;
    }

    @GetMapping("/issues")
    public ApiResponse<GitHubSnapshotPageResponse<GitHubIssueResponse>> issues(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.issues(workspaceId, projectId, page, size));
    }

    @GetMapping("/issues/{issueId}")
    public ApiResponse<GitHubIssueResponse> issue(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long issueId) {
        return ApiResponse.success(service.issue(workspaceId, projectId, issueId));
    }

    @GetMapping("/pull-requests")
    public ApiResponse<GitHubSnapshotPageResponse<GitHubPullRequestResponse>> pullRequests(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.pullRequests(workspaceId, projectId, page, size));
    }

    @GetMapping("/pull-requests/{pullRequestId}")
    public ApiResponse<GitHubPullRequestResponse> pullRequest(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long pullRequestId) {
        return ApiResponse.success(service.pullRequest(workspaceId, projectId, pullRequestId));
    }

    @GetMapping("/pull-requests/{pullRequestId}/reviews")
    public ApiResponse<List<GitHubPullRequestReviewResponse>> reviews(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @PathVariable @Positive long pullRequestId) {
        return ApiResponse.success(service.reviews(workspaceId, projectId, pullRequestId));
    }
}
