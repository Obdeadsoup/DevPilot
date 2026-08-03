package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Webhook 与 API 共用的 PR 当前快照 Upsert。GitHub PR stable ID 是身份，number 只是
 * Repository 范围展示编号；draft 与 OPEN/CLOSED/MERGED 状态分别保存。
 */
@Service
public class GitHubPullRequestApplicationService {
    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubPullRequestMapper mapper;
    private final GitHubPullRequestPersistenceService persistenceService;
    private final GitHubExternalContentPolicy contentPolicy;

    public GitHubPullRequestApplicationService(GitHubRepositoryMapper repositoryMapper,
                                               GitHubPullRequestMapper mapper,
                                               GitHubPullRequestPersistenceService persistenceService,
                                               GitHubExternalContentPolicy contentPolicy) {
        this.repositoryMapper = repositoryMapper;
        this.mapper = mapper;
        this.persistenceService = persistenceService;
        this.contentPolicy = contentPolicy;
    }

    /** 外部 github_updated_at 拒绝乱序，version 保护本地写竞争；两个入口必须走同一规则。 */
    public UpsertResult upsertPullRequest(UpsertGitHubPullRequestCommand raw) {
        UpsertGitHubPullRequestCommand command = normalize(raw);
        GitHubRepositoryEntity binding = requireBinding(command);
        GitHubPullRequestEntity existing = mapper.findByRepositoryAndGitHubId(
                command.githubRepositoryId(), command.githubPullRequestId()).orElse(null);
        if (existing != null) {
            return update(existing, command, binding.fullName());
        }
        try {
            return persistenceService.insertAndRecord(command, binding.fullName());
        } catch (DuplicateKeyException exception) {
            GitHubPullRequestEntity concurrent = mapper.findByRepositoryAndGitHubId(
                            command.githubRepositoryId(), command.githubPullRequestId())
                    .orElseThrow(this::stateConflict);
            return update(concurrent, command, binding.fullName());
        }
    }

    private UpsertResult update(
            GitHubPullRequestEntity existing,
            UpsertGitHubPullRequestCommand command,
            String fullName) {
        requireScope(command, existing);
        if (command.githubUpdatedAt().isBefore(existing.githubUpdatedAt())) {
            return new UpsertResult(existing.id(), false, false, true);
        }
        if (command.githubUpdatedAt().equals(existing.githubUpdatedAt())
                && command.contentHash().equals(existing.contentHash())) {
            return new UpsertResult(existing.id(), false, false, false);
        }
        return persistenceService.updateAndRecord(existing, command, fullName);
    }

    private UpsertGitHubPullRequestCommand normalize(UpsertGitHubPullRequestCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.githubPullRequestId() <= 0
                || command.pullRequestNumber() <= 0
                || command.status() == null
                || command.source() == null
                || command.githubCreatedAt() == null
                || command.githubUpdatedAt() == null) {
            throw invalid();
        }
        String title = contentPolicy.title(command.title());
        String body = contentPolicy.body(command.body());
        String headRef = contentPolicy.ref(command.headRef());
        String baseRef = contentPolicy.ref(command.baseRef());
        String headSha = contentPolicy.sha(command.headSha(), false);
        String baseSha = contentPolicy.sha(command.baseSha(), false);
        String mergeSha = contentPolicy.sha(command.mergeCommitSha(), true);
        String reviewers = contentPolicy.summaryJson(command.requestedReviewersJson());
        String assignees = contentPolicy.summaryJson(command.assigneeSummaryJson());
        String labels = contentPolicy.summaryJson(command.labelsJson());
        String url = contentPolicy.githubUrl(command.htmlUrl());
        String hash = contentPolicy.contentHash(
                command.githubPullRequestId(), command.githubIssueId(), command.pullRequestNumber(),
                title, body, command.status(), command.draft(), command.authorGitHubUserId(),
                command.authorLogin(), headRef, headSha, baseRef, baseSha, mergeSha, reviewers,
                assignees, labels, url, command.githubCreatedAt(), command.githubUpdatedAt(),
                command.githubClosedAt(), command.githubMergedAt());
        return new UpsertGitHubPullRequestCommand(
                command.workspaceId(), command.projectId(), command.repositoryBindingId(),
                command.githubRepositoryId(), contentPolicy.truncate(command.repositoryFullName(), 201),
                command.githubPullRequestId(), command.githubIssueId(), command.pullRequestNumber(),
                title, body, command.status(), command.draft(), command.authorGitHubUserId(),
                contentPolicy.login(command.authorLogin()), headRef, headSha, baseRef, baseSha,
                mergeSha, reviewers, assignees, labels, url, command.githubCreatedAt(),
                command.githubUpdatedAt(), command.githubClosedAt(), command.githubMergedAt(),
                command.source(), contentPolicy.truncate(command.sourceEventId(), 100),
                contentPolicy.truncate(command.webhookAction(), 50), hash);
    }

    private GitHubRepositoryEntity requireBinding(UpsertGitHubPullRequestCommand command) {
        GitHubRepositoryEntity binding = repositoryMapper
                .findByScope(command.workspaceId(), command.projectId(), command.repositoryBindingId())
                .orElseThrow(this::scopeConflict);
        if (binding.githubRepositoryId() != command.githubRepositoryId()) {
            throw scopeConflict();
        }
        return binding;
    }

    private void requireScope(
            UpsertGitHubPullRequestCommand command, GitHubPullRequestEntity existing) {
        if (existing.workspaceId() != command.workspaceId()
                || existing.projectId() != command.projectId()
                || existing.repositoryBindingId() != command.repositoryBindingId()
                || existing.githubRepositoryId() != command.githubRepositoryId()
                || existing.pullRequestNumber() != command.pullRequestNumber()) {
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

    public record UpsertResult(long pullRequestId, boolean inserted, boolean changed, boolean stale) { }
}
