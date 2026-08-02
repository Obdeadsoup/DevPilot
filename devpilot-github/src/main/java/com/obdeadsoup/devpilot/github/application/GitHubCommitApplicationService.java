package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubCommitEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubCommitMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

/**
 * Webhook 与 API 的统一 Commit Upsert Application Service。
 * 两个入口共用 Repository + SHA 唯一键，首次插入与 Commit Activity 位于同一短事务；
 * 重叠时间窗口和并发只会重复读取，数据库唯一索引保证不会重复产生业务结果。
 */
@Service
public class GitHubCommitApplicationService {

    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubCommitMapper commitMapper;
    private final GitHubCommitPersistenceService persistenceService;

    public GitHubCommitApplicationService(
            GitHubRepositoryMapper repositoryMapper,
            GitHubCommitMapper commitMapper,
            GitHubCommitPersistenceService persistenceService
    ) {
        this.repositoryMapper = repositoryMapper;
        this.commitMapper = commitMapper;
        this.persistenceService = persistenceService;
    }

    /**
     * 校验 Binding Scope 后插入或补充安全元数据。first_seen_source 永不更新；
     * DuplicateKeyException 是并发幂等的正常仲裁路径，而不是先查后插的替代品。
     *
     * @return inserted=true 表示本次首次落库并创建了一条 Commit Activity
     */
    public UpsertResult upsert(UpsertGitHubCommitCommand rawCommand) {
        UpsertGitHubCommitCommand command = normalized(rawCommand);
        GitHubRepositoryEntity binding = repositoryMapper.findByScope(
                        command.workspaceId(), command.projectId(), command.repositoryBindingId()
                )
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.COMMIT_SCOPE_CONFLICT));
        requireScope(command, binding);

        GitHubCommitEntity existing = commitMapper.findByRepositoryAndSha(
                command.githubRepositoryId(), command.commitSha()
        ).orElse(null);
        if (existing != null) {
            GitHubCommitEntity updated = persistenceService.updateExisting(command);
            return new UpsertResult(updated.id(), false);
        }

        try {
            long insertedId = persistenceService.insertAndRecord(command, binding.fullName());
            return new UpsertResult(insertedId, true);
        } catch (DuplicateKeyException exception) {
            // 竞争插入事务已完全回滚，再用新短事务读取唯一索引选出的胜者。
            GitHubCommitEntity concurrent = persistenceService.updateExisting(command);
            return new UpsertResult(concurrent.id(), false);
        }
    }

    private UpsertGitHubCommitCommand normalized(UpsertGitHubCommitCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String sha = Objects.requireNonNull(command.commitSha(), "commitSha must not be null")
                .toLowerCase(Locale.ROOT);
        if (!sha.matches("[0-9a-f]{40}")) {
            throw new BusinessException(GitHubSyncErrorCode.COMMIT_RESPONSE_INVALID);
        }
        Objects.requireNonNull(command.committedAt(), "committedAt must not be null");
        Objects.requireNonNull(command.source(), "source must not be null");
        return new UpsertGitHubCommitCommand(
                command.workspaceId(), command.projectId(), command.repositoryBindingId(),
                command.githubRepositoryId(), truncate(command.repositoryFullName(), 201), sha,
                truncate(command.message(), 2000), truncate(command.authorName(), 255),
                truncate(command.authorEmail(), 320), command.authorGitHubUserId(),
                truncate(command.authorLogin(), 100), command.committedAt(),
                truncate(command.htmlUrl(), 500), command.source()
        );
    }

    private void requireScope(UpsertGitHubCommitCommand command, GitHubRepositoryEntity binding) {
        if (binding.id() != command.repositoryBindingId()
                || binding.workspaceId() != command.workspaceId()
                || binding.projectId() != command.projectId()
                || binding.githubRepositoryId() != command.githubRepositoryId()) {
            throw new BusinessException(GitHubSyncErrorCode.COMMIT_SCOPE_CONFLICT);
        }
    }

    private String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }

    public record UpsertResult(long commitId, boolean inserted) {
    }
}
