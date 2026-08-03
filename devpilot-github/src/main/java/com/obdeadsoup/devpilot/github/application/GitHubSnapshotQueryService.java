package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubIssueResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubPullRequestResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubPullRequestReviewResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSnapshotPageResponse;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubIssueMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestReviewMapper;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Project 范围 GitHub 快照只读服务；RBAC 校验后 SQL 仍强制 workspaceId + projectId Scope。 */
@Service
public class GitHubSnapshotQueryService {

    private final CurrentUserProvider users;
    private final ProjectAuthorizationService authorization;
    private final GitHubIssueMapper issues;
    private final GitHubPullRequestMapper pullRequests;
    private final GitHubPullRequestReviewMapper reviews;

    public GitHubSnapshotQueryService(
            CurrentUserProvider users,
            ProjectAuthorizationService authorization,
            GitHubIssueMapper issues,
            GitHubPullRequestMapper pullRequests,
            GitHubPullRequestReviewMapper reviews) {
        this.users = users;
        this.authorization = authorization;
        this.issues = issues;
        this.pullRequests = pullRequests;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public GitHubSnapshotPageResponse<GitHubIssueResponse> issues(
            long workspaceId, long projectId, int page, int size) {
        requireRead(workspaceId, projectId);
        long total = issues.countByProject(workspaceId, projectId);
        List<GitHubIssueResponse> items = issues
                .findPageByProject(workspaceId, projectId, (long) (page - 1) * size, size)
                .stream()
                .map(GitHubIssueResponse::list)
                .toList();
        return new GitHubSnapshotPageResponse<>(items, page, size, total, pages(total, size));
    }

    @Transactional(readOnly = true)
    public GitHubIssueResponse issue(long workspaceId, long projectId, long id) {
        requireRead(workspaceId, projectId);
        return issues.findByProjectAndId(workspaceId, projectId, id)
                .map(GitHubIssueResponse::detail)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public GitHubSnapshotPageResponse<GitHubPullRequestResponse> pullRequests(
            long workspaceId, long projectId, int page, int size) {
        requireRead(workspaceId, projectId);
        long total = pullRequests.countByProject(workspaceId, projectId);
        List<GitHubPullRequestResponse> items = pullRequests
                .findPageByProject(workspaceId, projectId, (long) (page - 1) * size, size)
                .stream()
                .map(GitHubPullRequestResponse::list)
                .toList();
        return new GitHubSnapshotPageResponse<>(items, page, size, total, pages(total, size));
    }

    @Transactional(readOnly = true)
    public GitHubPullRequestResponse pullRequest(long workspaceId, long projectId, long id) {
        requireRead(workspaceId, projectId);
        return pullRequests.findByProjectAndId(workspaceId, projectId, id)
                .map(GitHubPullRequestResponse::detail)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<GitHubPullRequestReviewResponse> reviews(long workspaceId, long projectId, long pullRequestId) {
        requireRead(workspaceId, projectId);
        pullRequests.findByProjectAndId(workspaceId, projectId, pullRequestId)
                .orElseThrow(this::notFound);
        return reviews.findByPullRequestScope(workspaceId, projectId, pullRequestId)
                .stream()
                .map(GitHubPullRequestReviewResponse::from)
                .toList();
    }

    private void requireRead(long workspaceId, long projectId) {
        authorization.requirePermission(
                users.requireUserId(), workspaceId, projectId, ProjectPermission.PROJECT_READ);
    }

    private long pages(long total, int size) {
        return total == 0 ? 0 : (total + size - 1) / size;
    }

    private BusinessException notFound() {
        return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_NOT_FOUND);
    }
}
