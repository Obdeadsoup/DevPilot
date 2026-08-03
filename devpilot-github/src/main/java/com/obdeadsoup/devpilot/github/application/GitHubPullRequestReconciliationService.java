package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubPullRequest;
import com.obdeadsoup.devpilot.github.application.client.GitHubPullRequestClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * PR 对账按 updated desc 翻页，到达 overlap 边界即停止；变化的 PR 会因 github_updated_at
 * 更新而成为 Review 有界候选，不直接在此处对全部历史 PR 查询 Reviews。
 */
@Service
public class GitHubPullRequestReconciliationService extends AbstractGitHubSnapshotReconciliationService {

    private final GitHubPullRequestClient client;
    private final GitHubPullRequestApplicationService application;

    public GitHubPullRequestReconciliationService(
            GitHubSyncRunStateService runs,
            GitHubRepositoryMapper repositories,
            GitHubRepositoryMetadataClient metadata,
            GitHubSyncCheckpointService checkpoints,
            GitHubSyncFailureClassifier failures,
            GitHubReconciliationProperties properties,
            Clock clock,
            GitHubPullRequestClient client,
            GitHubPullRequestApplicationService application) {
        super(runs, repositories, metadata, checkpoints, failures, properties, clock);
        this.client = client;
        this.application = application;
    }

    @Override
    public GitHubSyncResourceType resourceType() {
        return GitHubSyncResourceType.PULL_REQUEST;
    }

    @Override
    protected LocalDateTime reconcilePages(
            GitHubSyncTarget target, Instant since, GitHubSnapshotSource source) {
        GitHubPageCursor cursor = GitHubPageCursor.empty();
        LocalDateTime boundary = null;
        boolean reachedBoundary = false;
        do {
            GitHubPage<GitHubPullRequest> page = client.listPullRequests(
                    target.ownerLogin(),
                    target.repositoryName(),
                    properties.perPage(),
                    target.apiCredentialRef(),
                    cursor);
            for (GitHubPullRequest pullRequest : page.items()) {
                if (pullRequest.updatedAt().isBefore(since)) {
                    reachedBoundary = true;
                    break;
                }
                application.upsertPullRequest(command(target, pullRequest, source));
                LocalDateTime updatedAt = utc(pullRequest.updatedAt());
                if (boundary == null || updatedAt.isAfter(boundary)) {
                    boundary = updatedAt;
                }
            }
            cursor = page.cursor();
        } while (!reachedBoundary && cursor.hasNext());
        return boundary;
    }

    private UpsertGitHubPullRequestCommand command(
            GitHubSyncTarget target, GitHubPullRequest pullRequest, GitHubSnapshotSource source) {
        boolean merged = pullRequest.mergedAt() != null;
        GitHubPullRequestStatus status = GitHubPullRequestStatus.from(
                pullRequest.state(), merged, merged);
        return new UpsertGitHubPullRequestCommand(
                target.workspaceId(), target.projectId(), target.bindingId(), target.githubRepositoryId(),
                target.fullName(), pullRequest.githubPullRequestId(), null, pullRequest.number(),
                pullRequest.title(), pullRequest.body(), status, pullRequest.draft(),
                pullRequest.authorGitHubUserId(), pullRequest.authorLogin(), pullRequest.headRef(),
                pullRequest.headSha(), pullRequest.baseRef(), pullRequest.baseSha(),
                pullRequest.mergeCommitSha(), pullRequest.requestedReviewersJson(),
                pullRequest.assigneesJson(), pullRequest.labelsJson(), pullRequest.htmlUrl(),
                utc(pullRequest.createdAt()), utc(pullRequest.updatedAt()), utc(pullRequest.closedAt()),
                utc(pullRequest.mergedAt()), source, null, null, null);
    }

    private LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
