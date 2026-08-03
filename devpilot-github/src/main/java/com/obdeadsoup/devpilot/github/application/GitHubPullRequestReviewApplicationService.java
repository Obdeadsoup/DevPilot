package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestReviewEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestReviewMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Review 的统一 Upsert；Review 使用独立 github_review_id，而 PR number 只用于解析本地 PR 外键。
 * reviewer login 永不映射为本地用户或权限。
 */
@Service
public class GitHubPullRequestReviewApplicationService {

    private final GitHubRepositoryMapper repositories;
    private final GitHubPullRequestMapper pullRequests;
    private final GitHubPullRequestReviewMapper reviews;
    private final GitHubPullRequestReviewPersistenceService persistence;
    private final GitHubExternalContentPolicy policy;

    public GitHubPullRequestReviewApplicationService(GitHubRepositoryMapper repositories,
                                                     GitHubPullRequestMapper pullRequests,
                                                     GitHubPullRequestReviewMapper reviews,
                                                     GitHubPullRequestReviewPersistenceService persistence,
                                                     GitHubExternalContentPolicy policy) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.reviews = reviews;
        this.persistence = persistence;
        this.policy = policy;
    }

    /**
     * 解析本地 PR 外键后按独立 Review ID Upsert；更早 updatedAt 不覆盖，相同 Hash 不重复。
     * 初始 Backfill 不创建 Activity，Webhook/API 后续语义变化才由短事务记录。
     */
    public UpsertResult upsertReview(UpsertGitHubPullRequestReviewCommand raw) {
        UpsertGitHubPullRequestReviewCommand command = normalize(raw);
        requireBinding(command);
        GitHubPullRequestEntity pullRequest = pullRequests
                .findByRepositoryAndNumber(command.githubRepositoryId(), command.pullRequestNumber())
                .orElseThrow(this::scopeConflict);
        requirePrScope(command, pullRequest);
        GitHubPullRequestReviewEntity existing = reviews
                .findByRepositoryAndGitHubId(command.githubRepositoryId(), command.githubReviewId())
                .orElse(null);
        if (existing != null) {
            return update(existing, pullRequest, command);
        }
        try {
            return persistence.insertAndRecord(pullRequest, command);
        } catch (DuplicateKeyException exception) {
            GitHubPullRequestReviewEntity concurrent = reviews
                    .findByRepositoryAndGitHubId(command.githubRepositoryId(), command.githubReviewId())
                    .orElseThrow(this::stateConflict);
            return update(concurrent, pullRequest, command);
        }
    }

    private UpsertResult update(
            GitHubPullRequestReviewEntity existing,
            GitHubPullRequestEntity pullRequest,
            UpsertGitHubPullRequestReviewCommand command) {
        if (existing.pullRequestId() != pullRequest.id()
                || existing.workspaceId() != command.workspaceId()
                || existing.projectId() != command.projectId()
                || existing.repositoryBindingId() != command.repositoryBindingId()) {
            throw scopeConflict();
        }
        if (command.githubUpdatedAt().isBefore(existing.githubUpdatedAt())) {
            return new UpsertResult(existing.id(), false, false, true);
        }
        if (command.githubUpdatedAt().equals(existing.githubUpdatedAt())
                && command.contentHash().equals(existing.contentHash())) {
            return new UpsertResult(existing.id(), false, false, false);
        }
        return persistence.updateAndRecord(existing, command);
    }

    private UpsertGitHubPullRequestReviewCommand normalize(
            UpsertGitHubPullRequestReviewCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.githubReviewId() <= 0
                || command.pullRequestNumber() <= 0
                || command.status() == null
                || command.source() == null
                || command.submittedAt() == null
                || command.githubUpdatedAt() == null) {
            throw invalid();
        }
        String body = policy.body(command.body());
        String commitSha = policy.sha(command.commitSha(), false);
        String url = policy.githubUrl(command.htmlUrl());
        String hash = policy.contentHash(
                command.githubReviewId(), command.pullRequestNumber(), command.reviewerGitHubUserId(),
                command.reviewerLogin(), command.status(), body, commitSha, url, command.submittedAt(),
                command.githubUpdatedAt());
        return new UpsertGitHubPullRequestReviewCommand(
                command.workspaceId(), command.projectId(), command.repositoryBindingId(),
                command.githubRepositoryId(), policy.truncate(command.repositoryFullName(), 201),
                command.pullRequestNumber(), command.githubReviewId(), command.reviewerGitHubUserId(),
                policy.login(command.reviewerLogin()), command.status(), body, commitSha, url,
                command.submittedAt(), command.githubUpdatedAt(), command.source(),
                policy.truncate(command.sourceEventId(), 100),
                policy.truncate(command.webhookAction(), 50), hash);
    }

    private void requireBinding(UpsertGitHubPullRequestReviewCommand command) {
        GitHubRepositoryEntity binding = repositories
                .findByScope(command.workspaceId(), command.projectId(), command.repositoryBindingId())
                .orElseThrow(this::scopeConflict);
        if (binding.githubRepositoryId() != command.githubRepositoryId()) {
            throw scopeConflict();
        }
    }

    private void requirePrScope(
            UpsertGitHubPullRequestReviewCommand command, GitHubPullRequestEntity pullRequest) {
        if (pullRequest.workspaceId() != command.workspaceId()
                || pullRequest.projectId() != command.projectId()
                || pullRequest.repositoryBindingId() != command.repositoryBindingId()) {
            throw scopeConflict();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_RESPONSE_INVALID);
    }

    private BusinessException scopeConflict() {
        return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_SCOPE_CONFLICT);
    }

    private BusinessException stateConflict() {
        return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT);
    }

    public record UpsertResult(long reviewId, boolean inserted, boolean changed, boolean stale) {
    }
}
