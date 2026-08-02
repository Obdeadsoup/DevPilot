package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubCommitEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubCommitMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commit 行与首次 Activity 的独立短事务执行器，由统一 Upsert 服务编排。 */
@Service
public class GitHubCommitPersistenceService {

    private final GitHubCommitMapper commitMapper;
    private final ProjectActivityService activityService;

    public GitHubCommitPersistenceService(
            GitHubCommitMapper commitMapper,
            ProjectActivityService activityService
    ) {
        this.commitMapper = commitMapper;
        this.activityService = activityService;
    }

    /** 独立事务使 DuplicateKey 回滚完成后，编排层能在新事务中安全读取并发胜者。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long insertAndRecord(UpsertGitHubCommitCommand command, String repositoryFullName) {
        commitMapper.insert(command);
        GitHubCommitEntity inserted = required(command);
        recordCommitActivity(command, repositoryFullName);
        return inserted.id();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GitHubCommitEntity updateExisting(UpsertGitHubCommitCommand command) {
        GitHubCommitEntity existing = required(command);
        requireScope(command, existing);
        // 乐观锁只防止旧元数据覆盖新元数据；唯一索引已独立完成“最多一条 Commit”的仲裁。
        commitMapper.updateSafeMetadata(existing.id(), existing.version(), command);
        return existing;
    }

    private GitHubCommitEntity required(UpsertGitHubCommitCommand command) {
        return commitMapper.findByRepositoryAndSha(command.githubRepositoryId(), command.commitSha())
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.COMMIT_SCOPE_CONFLICT));
    }

    private void requireScope(UpsertGitHubCommitCommand command, GitHubCommitEntity commit) {
        if (commit.repositoryBindingId() != command.repositoryBindingId()
                || commit.workspaceId() != command.workspaceId()
                || commit.projectId() != command.projectId()
                || commit.githubRepositoryId() != command.githubRepositoryId()) {
            throw new BusinessException(GitHubSyncErrorCode.COMMIT_SCOPE_CONFLICT);
        }
    }

    private void recordCommitActivity(UpsertGitHubCommitCommand command, String repositoryFullName) {
        String shortSha = command.commitSha().substring(0, 7);
        activityService.recordGitHubActivity(new RecordProjectActivityCommand(
                command.workspaceId(), command.projectId(), command.githubRepositoryId(),
                truncate(repositoryFullName, 201), ProjectActivitySourceType.GITHUB,
                ProjectActivityType.GITHUB_COMMIT_DISCOVERED,
                "commit:" + command.githubRepositoryId() + ":" + command.commitSha(),
                command.authorGitHubUserId(), truncate(command.authorLogin(), 100),
                null, null, command.commitSha(), null, truncate(command.message(), 1000),
                "Commit " + shortSha + " discovered", truncate(command.message(), 2000),
                truncate(command.htmlUrl(), 500), command.committedAt()
        ));
    }

    private String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }
}
