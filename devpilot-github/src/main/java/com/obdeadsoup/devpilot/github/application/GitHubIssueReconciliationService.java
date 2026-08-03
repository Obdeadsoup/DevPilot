package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.client.GitHubApiException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiFailureType;
import com.obdeadsoup.devpilot.github.application.client.GitHubIssue;
import com.obdeadsoup.devpilot.github.application.client.GitHubIssueClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubIssueStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Issue 增量对账：Checkpoint-overlap 后分页调用 Issues API（Client 已过滤 PR），每条数据仍走
 * Webhook 共用 Upsert；只有整轮数据成功后才推进可靠 Checkpoint。
 */
@Service
public class GitHubIssueReconciliationService extends AbstractGitHubSnapshotReconciliationService {

    private final GitHubIssueClient client;
    private final GitHubIssueApplicationService application;

    public GitHubIssueReconciliationService(
            GitHubSyncRunStateService runs,
            GitHubRepositoryMapper repositories,
            GitHubRepositoryMetadataClient metadata,
            GitHubSyncCheckpointService checkpoints,
            GitHubSyncFailureClassifier failures,
            GitHubReconciliationProperties properties,
            Clock clock,
            GitHubIssueClient client,
            GitHubIssueApplicationService application) {
        super(runs, repositories, metadata, checkpoints, failures, properties, clock);
        this.client = client;
        this.application = application;
    }

    @Override
    public GitHubSyncResourceType resourceType() {
        return GitHubSyncResourceType.ISSUE;
    }

    @Override
    protected LocalDateTime reconcilePages(
            GitHubSyncTarget target, Instant since, GitHubSnapshotSource source) {
        GitHubPageCursor cursor = GitHubPageCursor.empty();
        LocalDateTime boundary = null;
        do {
            GitHubPage<GitHubIssue> page = client.listIssues(
                    target.ownerLogin(),
                    target.repositoryName(),
                    since,
                    properties.perPage(),
                    target.apiCredentialRef(),
                    cursor);
            for (GitHubIssue issue : page.items()) {
                application.upsertIssue(command(target, issue, source));
                LocalDateTime updatedAt = utc(issue.updatedAt());
                if (boundary == null || updatedAt.isAfter(boundary)) {
                    boundary = updatedAt;
                }
            }
            cursor = page.cursor();
        } while (cursor.hasNext());
        return boundary;
    }

    private UpsertGitHubIssueCommand command(
            GitHubSyncTarget target, GitHubIssue issue, GitHubSnapshotSource source) {
        GitHubIssueStatus status;
        try {
            status = GitHubIssueStatus.valueOf(issue.state().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new GitHubApiException(
                    GitHubApiFailureType.MALFORMED_RESPONSE,
                    false,
                    null,
                    null,
                    "GitHub issue state is invalid",
                    null,
                    null);
        }
        return new UpsertGitHubIssueCommand(
                target.workspaceId(), target.projectId(), target.bindingId(), target.githubRepositoryId(),
                target.fullName(), issue.githubIssueId(), issue.number(), issue.title(), issue.body(),
                status, issue.stateReason(), issue.authorGitHubUserId(), issue.authorLogin(),
                issue.assigneesJson(), issue.labelsJson(), issue.htmlUrl(), utc(issue.createdAt()),
                utc(issue.updatedAt()), utc(issue.closedAt()), source, null, null, null);
    }

    private LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
