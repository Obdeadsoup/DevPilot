package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubIssueMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Webhook、API Backfill 与 API Reconciliation 的统一 Issue 当前快照 Upsert。
 * github_updated_at 防止 GitHub 旧事件覆盖新快照；本地 version 只解决同一行的并发写竞争。
 */
@Service
public class GitHubIssueApplicationService {

    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubIssueMapper issueMapper;
    private final GitHubIssuePersistenceService persistenceService;
    private final GitHubExternalContentPolicy contentPolicy;

    public GitHubIssueApplicationService(GitHubRepositoryMapper repositoryMapper,
                                         GitHubIssueMapper issueMapper,
                                         GitHubIssuePersistenceService persistenceService,
                                         GitHubExternalContentPolicy contentPolicy) {
        this.repositoryMapper = repositoryMapper;
        this.issueMapper = issueMapper;
        this.persistenceService = persistenceService;
        this.contentPolicy = contentPolicy;
    }

    /**
     * 保存当前快照而非完整事件历史。旧 updatedAt 和相同 Hash 都是无副作用幂等；
     * 初始 API_BACKFILL 不创建 Activity，避免把历史对象伪装成刚发生的事件。
     */
    public UpsertResult upsertIssue(UpsertGitHubIssueCommand raw) {
        UpsertGitHubIssueCommand command = normalize(raw);
        GitHubRepositoryEntity binding = requireBinding(command);
        GitHubIssueEntity existing = issueMapper.findByRepositoryAndGitHubId(
                command.githubRepositoryId(), command.githubIssueId()).orElse(null);
        if (existing != null) return update(existing, command, binding.fullName());
        try {
            return persistenceService.insertAndRecord(command, binding.fullName());
        } catch (DuplicateKeyException exception) {
            GitHubIssueEntity concurrent = issueMapper.findByRepositoryAndGitHubId(
                    command.githubRepositoryId(), command.githubIssueId())
                    .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT));
            return update(concurrent, command, binding.fullName());
        }
    }

    private UpsertResult update(GitHubIssueEntity existing, UpsertGitHubIssueCommand command,
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

    private UpsertGitHubIssueCommand normalize(UpsertGitHubIssueCommand c) {
        Objects.requireNonNull(c, "command must not be null");
        if (c.githubIssueId() <= 0 || c.issueNumber() <= 0 || c.githubCreatedAt() == null
                || c.githubUpdatedAt() == null || c.status() == null || c.source() == null) throw invalid();
        String title = contentPolicy.title(c.title());
        String body = contentPolicy.body(c.body());
        String assignees = contentPolicy.summaryJson(c.assigneeSummaryJson());
        String labels = contentPolicy.summaryJson(c.labelsJson());
        String url = contentPolicy.githubUrl(c.htmlUrl());
        String hash = contentPolicy.contentHash(c.githubIssueId(), c.issueNumber(), title, body,
                c.status(), c.stateReason(), c.authorGitHubUserId(), c.authorLogin(), assignees,
                labels, url, c.githubCreatedAt(), c.githubUpdatedAt(), c.githubClosedAt());
        return new UpsertGitHubIssueCommand(c.workspaceId(), c.projectId(), c.repositoryBindingId(),
                c.githubRepositoryId(), contentPolicy.truncate(c.repositoryFullName(), 201), c.githubIssueId(),
                c.issueNumber(), title, body, c.status(), contentPolicy.truncate(c.stateReason(), 100),
                c.authorGitHubUserId(), contentPolicy.login(c.authorLogin()), assignees, labels, url,
                c.githubCreatedAt(), c.githubUpdatedAt(), c.githubClosedAt(), c.source(),
                contentPolicy.truncate(c.sourceEventId(), 100), contentPolicy.truncate(c.webhookAction(), 50), hash);
    }

    private GitHubRepositoryEntity requireBinding(UpsertGitHubIssueCommand c) {
        GitHubRepositoryEntity binding = repositoryMapper.findByScope(c.workspaceId(), c.projectId(), c.repositoryBindingId())
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SNAPSHOT_SCOPE_CONFLICT));
        if (binding.githubRepositoryId() != c.githubRepositoryId()) throw scope();
        return binding;
    }

    private void requireScope(UpsertGitHubIssueCommand c, GitHubIssueEntity e) {
        if (e.workspaceId()!=c.workspaceId() || e.projectId()!=c.projectId()
                || e.repositoryBindingId()!=c.repositoryBindingId()
                || e.githubRepositoryId()!=c.githubRepositoryId()
                || e.issueNumber()!=c.issueNumber()) throw scope();
    }

    private BusinessException invalid() { return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_RESPONSE_INVALID); }
    private BusinessException scope() { return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_SCOPE_CONFLICT); }

    public record UpsertResult(long issueId, boolean inserted, boolean changed, boolean stale) { }
}
