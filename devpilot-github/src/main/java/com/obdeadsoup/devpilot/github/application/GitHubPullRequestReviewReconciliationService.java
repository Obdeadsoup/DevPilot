package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.client.GitHubApiException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiFailureType;
import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubPullRequestReview;
import com.obdeadsoup.devpilot.github.application.client.GitHubPullRequestReviewClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestReviewStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Review 对账只扫描“近期活跃且 reviews_synced_at 落后”的有限 PR 批次，并在每个 PR 全部分页成功后
 * 推进 PR 级水位；它不是对所有历史 PR 的无限 N+1 全量扫描。
 */
@Service
public class GitHubPullRequestReviewReconciliationService extends AbstractGitHubSnapshotReconciliationService {

    private final GitHubPullRequestMapper pullRequests;
    private final GitHubPullRequestReviewClient client;
    private final GitHubPullRequestReviewApplicationService application;
    private final GitHubReviewSyncProgressService progress;

    public GitHubPullRequestReviewReconciliationService(
            GitHubSyncRunStateService runs,
            GitHubRepositoryMapper repositories,
            GitHubRepositoryMetadataClient metadata,
            GitHubSyncCheckpointService checkpoints,
            GitHubSyncFailureClassifier failures,
            GitHubReconciliationProperties properties,
            Clock clock,
            GitHubPullRequestMapper pullRequests,
            GitHubPullRequestReviewClient client,
            GitHubPullRequestReviewApplicationService application,
            GitHubReviewSyncProgressService progress) {
        super(runs, repositories, metadata, checkpoints, failures, properties, clock);
        this.pullRequests = pullRequests;
        this.client = client;
        this.application = application;
        this.progress = progress;
    }

    @Override
    public GitHubSyncResourceType resourceType() {
        return GitHubSyncResourceType.PULL_REQUEST_REVIEW;
    }

    @Override
    protected LocalDateTime reconcilePages(
            GitHubSyncTarget target, Instant since, GitHubSnapshotSource source) {
        LocalDateTime activeSince = LocalDateTime.ofInstant(
                clock.instant().minus(properties.initialLookback()), ZoneOffset.UTC);
        LocalDateTime boundary = null;
        for (GitHubPullRequestEntity pullRequest : pullRequests.findReviewCandidates(
                target.bindingId(), activeSince, properties.batchSize())) {
            GitHubPageCursor cursor = GitHubPageCursor.empty();
            do {
                GitHubPage<GitHubPullRequestReview> page = client.listReviews(
                        target.ownerLogin(),
                        target.repositoryName(),
                        pullRequest.pullRequestNumber(),
                        properties.perPage(),
                        target.apiCredentialRef(),
                        cursor);
                for (GitHubPullRequestReview review : page.items()) {
                    application.upsertReview(command(target, pullRequest, review, source));
                }
                cursor = page.cursor();
            } while (cursor.hasNext());

            progress.complete(pullRequest, LocalDateTime.now(clock));
            if (boundary == null || pullRequest.githubUpdatedAt().isAfter(boundary)) {
                boundary = pullRequest.githubUpdatedAt();
            }
        }
        return boundary == null ? LocalDateTime.now(clock) : boundary;
    }

    private UpsertGitHubPullRequestReviewCommand command(
            GitHubSyncTarget target,
            GitHubPullRequestEntity pullRequest,
            GitHubPullRequestReview review,
            GitHubSnapshotSource source) {
        GitHubPullRequestReviewStatus status;
        try {
            status = GitHubPullRequestReviewStatus.from(review.state());
        } catch (RuntimeException exception) {
            throw new GitHubApiException(
                    GitHubApiFailureType.MALFORMED_RESPONSE,
                    false,
                    null,
                    null,
                    "GitHub review state is invalid",
                    null,
                    null);
        }
        return new UpsertGitHubPullRequestReviewCommand(
                target.workspaceId(), target.projectId(), target.bindingId(), target.githubRepositoryId(),
                target.fullName(), pullRequest.pullRequestNumber(), review.githubReviewId(),
                review.reviewerGitHubUserId(), review.reviewerLogin(), status, review.body(),
                review.commitSha(), review.htmlUrl(), utc(review.submittedAt()), utc(review.updatedAt()),
                source, null, null, null);
    }

    private LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
