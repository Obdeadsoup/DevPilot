package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestReviewEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestReviewMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Review 快照和 Activity 的独立短事务边界。 */
@Service
public class GitHubPullRequestReviewPersistenceService {

    private final GitHubPullRequestReviewMapper mapper;
    private final GitHubSnapshotDiffService diff;
    private final ProjectActivityService activities;

    public GitHubPullRequestReviewPersistenceService(GitHubPullRequestReviewMapper mapper,
                                                     GitHubSnapshotDiffService diff,
                                                     ProjectActivityService activities) {
        this.mapper = mapper;
        this.diff = diff;
        this.activities = activities;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GitHubPullRequestReviewApplicationService.UpsertResult insertAndRecord(
            GitHubPullRequestEntity pullRequest, UpsertGitHubPullRequestReviewCommand command) {
        mapper.insert(pullRequest.id(), command);
        GitHubPullRequestReviewEntity inserted = required(command);
        record(command, diff.review(command));
        return new GitHubPullRequestReviewApplicationService.UpsertResult(
                inserted.id(), true, true, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GitHubPullRequestReviewApplicationService.UpsertResult updateAndRecord(
            GitHubPullRequestReviewEntity existing,
            UpsertGitHubPullRequestReviewCommand command) {
        if (mapper.updateSnapshot(existing.id(), existing.version(), command) != 1) {
            GitHubPullRequestReviewEntity current = required(command);
            if (current.githubUpdatedAt().isAfter(command.githubUpdatedAt())
                    || (current.githubUpdatedAt().equals(command.githubUpdatedAt())
                    && current.contentHash().equals(command.contentHash()))) {
                return new GitHubPullRequestReviewApplicationService.UpsertResult(
                        current.id(), false, false,
                        current.githubUpdatedAt().isAfter(command.githubUpdatedAt()));
            }
            throw new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT);
        }
        record(command, diff.review(command));
        return new GitHubPullRequestReviewApplicationService.UpsertResult(
                existing.id(), false, true, false);
    }

    private GitHubPullRequestReviewEntity required(UpsertGitHubPullRequestReviewCommand command) {
        return mapper.findByRepositoryAndGitHubId(
                        command.githubRepositoryId(), command.githubReviewId())
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT));
    }

    private void record(UpsertGitHubPullRequestReviewCommand command, ProjectActivityType type) {
        if (command.source() == GitHubSnapshotSource.API_BACKFILL
                || (command.source() == GitHubSnapshotSource.WEBHOOK
                && command.sourceEventId() == null)) {
            return;
        }
        String sourceId = command.sourceEventId() != null
                ? command.sourceEventId()
                : "review:" + command.githubReviewId() + ":" + command.contentHash().substring(0, 32);
        activities.recordGitHubActivity(new RecordProjectActivityCommand(
                command.workspaceId(), command.projectId(), command.githubRepositoryId(),
                command.repositoryFullName(), ProjectActivitySourceType.GITHUB, type, sourceId,
                command.reviewerGitHubUserId(), command.reviewerLogin(), null, null, null, null, null,
                "Review on pull request #" + command.pullRequestNumber(),
                "GitHub review state: " + command.status().name(), command.htmlUrl(), command.submittedAt()));
    }
}
