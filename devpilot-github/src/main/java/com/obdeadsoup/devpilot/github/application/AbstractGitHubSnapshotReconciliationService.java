package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.client.GitHubConditionalRequest;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

abstract class AbstractGitHubSnapshotReconciliationService implements GitHubReconciliationWorker {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    protected final GitHubSyncRunStateService runs;
    protected final GitHubSyncCheckpointService checkpoints;
    protected final GitHubReconciliationProperties properties;
    protected final Clock clock;
    private final GitHubRepositoryMapper repositories;
    private final GitHubRepositoryMetadataClient metadata;
    private final GitHubSyncFailureClassifier failures;

    AbstractGitHubSnapshotReconciliationService(
            GitHubSyncRunStateService runs,
            GitHubRepositoryMapper repositories,
            GitHubRepositoryMetadataClient metadata,
            GitHubSyncCheckpointService checkpoints,
            GitHubSyncFailureClassifier failures,
            GitHubReconciliationProperties properties,
            Clock clock) {
        this.runs = runs;
        this.repositories = repositories;
        this.metadata = metadata;
        this.checkpoints = checkpoints;
        this.failures = failures;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public final void reconcile(long runId) {
        Optional<GitHubSyncRunEntity> claimed = runs.claim(runId);
        if (claimed.isEmpty()) {
            return;
        }
        GitHubSyncRunEntity run = claimed.get();
        try {
            GitHubSyncTarget target = repositories.findSyncTarget(run.repositoryBindingId())
                    .filter(GitHubSyncTarget::isEligible)
                    .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_TARGET_UNAVAILABLE));
            GitHubSyncCheckpointEntity checkpoint = checkpoints.getOrCreate(target.bindingId(), resourceType());
            Instant since = checkpoints.calculateSince(checkpoint, clock.instant());
            var verified = metadata.getRepository(
                    target.ownerLogin(),
                    target.repositoryName(),
                    target.apiCredentialRef(),
                    GitHubConditionalRequest.none());
            if (verified.body() == null
                    || verified.body().githubRepositoryId() != target.githubRepositoryId()) {
                throw new BusinessException(GitHubSyncErrorCode.REPOSITORY_ID_MISMATCH);
            }
            GitHubSnapshotSource source = checkpoint.lastSuccessfulSyncAt() == null
                    ? GitHubSnapshotSource.API_BACKFILL
                    : GitHubSnapshotSource.API_RECONCILE;
            LocalDateTime boundary = reconcilePages(target, since, source);
            runs.complete(run, checkpoint, boundary, null);
        } catch (RuntimeException exception) {
            var failure = failures.classify(exception);
            String result = runs.handleFailure(run, failure)
                    .map(Enum::name)
                    .orElse("UNCHANGED");
            logger.warn(
                    "GitHub snapshot reconciliation failed runId={} resource={} bindingId={} errorCode={} resultStatus={}",
                    run.id(),
                    run.resourceType(),
                    run.repositoryBindingId(),
                    failure.stableErrorCode(),
                    result);
        }
    }

    protected abstract LocalDateTime reconcilePages(
            GitHubSyncTarget target, Instant since, GitHubSnapshotSource source);
}
