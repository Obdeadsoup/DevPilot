package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PR 快照与最多一条语义 Activity 的独立短事务边界。 */
@Service
public class GitHubPullRequestPersistenceService {

    private final GitHubPullRequestMapper mapper;
    private final GitHubSnapshotDiffService diff;
    private final ProjectActivityService activities;

    public GitHubPullRequestPersistenceService(GitHubPullRequestMapper mapper,
                                               GitHubSnapshotDiffService diff,
                                               ProjectActivityService activities) {
        this.mapper = mapper;
        this.diff = diff;
        this.activities = activities;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GitHubPullRequestApplicationService.UpsertResult insertAndRecord(
            UpsertGitHubPullRequestCommand command, String fullName) {
        mapper.insert(command);
        GitHubPullRequestEntity inserted = required(command);
        record(command, fullName, diff.pullRequest(null, command));
        return new GitHubPullRequestApplicationService.UpsertResult(inserted.id(), true, true, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GitHubPullRequestApplicationService.UpsertResult updateAndRecord(
            GitHubPullRequestEntity existing,
            UpsertGitHubPullRequestCommand command,
            String fullName) {
        ProjectActivityType type = diff.pullRequest(existing, command);
        if (mapper.updateSnapshot(existing.id(), existing.version(), command) != 1) {
            GitHubPullRequestEntity current = required(command);
            if (current.githubUpdatedAt().isAfter(command.githubUpdatedAt())
                    || (current.githubUpdatedAt().equals(command.githubUpdatedAt())
                    && current.contentHash().equals(command.contentHash()))) {
                return new GitHubPullRequestApplicationService.UpsertResult(
                        current.id(), false, false,
                        current.githubUpdatedAt().isAfter(command.githubUpdatedAt()));
            }
            throw new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT);
        }
        record(command, fullName, type);
        return new GitHubPullRequestApplicationService.UpsertResult(existing.id(), false, true, false);
    }

    private GitHubPullRequestEntity required(UpsertGitHubPullRequestCommand command) {
        return mapper.findByRepositoryAndGitHubId(
                        command.githubRepositoryId(), command.githubPullRequestId())
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT));
    }

    private void record(
            UpsertGitHubPullRequestCommand command, String fullName, ProjectActivityType type) {
        if (type == null
                || command.source() == GitHubSnapshotSource.API_BACKFILL
                || (command.source() == GitHubSnapshotSource.WEBHOOK
                && command.sourceEventId() == null)) {
            return;
        }
        String sourceId = command.sourceEventId() != null
                ? command.sourceEventId()
                : "pr:" + command.githubPullRequestId() + ":" + command.contentHash().substring(0, 32);
        activities.recordGitHubActivity(new RecordProjectActivityCommand(
                command.workspaceId(), command.projectId(), command.githubRepositoryId(), fullName,
                ProjectActivitySourceType.GITHUB, type, sourceId, command.authorGitHubUserId(),
                command.authorLogin(), null, null, command.headSha(), null, null,
                "Pull request #" + command.pullRequestNumber() + ": " + safe(command.title()),
                "GitHub pull request #" + command.pullRequestNumber() + " snapshot changed",
                command.htmlUrl(), command.githubUpdatedAt()));
    }

    private String safe(String value) {
        return value.length() <= 210 ? value : value.substring(0, 210);
    }
}
