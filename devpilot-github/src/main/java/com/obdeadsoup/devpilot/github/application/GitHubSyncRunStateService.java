package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.GitHubSyncFailureClassifier.Classification;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncRunStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Sync Run 条件状态迁移与事务边界；开放 Run 唯一键和 version 分别保护创建与状态抢占。 */
@Service
public class GitHubSyncRunStateService {

    private final GitHubSyncRunMapper runMapper;
    private final GitHubSyncCheckpointService checkpointService;
    private final GitHubSyncRetryPolicy retryPolicy;
    private final Clock clock;

    public GitHubSyncRunStateService(
            GitHubSyncRunMapper runMapper,
            GitHubSyncCheckpointService checkpointService,
            GitHubSyncRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.runMapper = runMapper;
        this.checkpointService = checkpointService;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public CreationResult createOrGetOpen(
            long bindingId,
            GitHubSyncTriggerType triggerType,
            Long requestedBy
    ) {
        Optional<GitHubSyncRunEntity> existing = runMapper.findOpenCommitRun(bindingId);
        if (existing.isPresent()) {
            return new CreationResult(existing.get(), true);
        }
        try {
            runMapper.insertPending(bindingId, triggerType.name(), requestedBy);
        } catch (DuplicateKeyException exception) {
            return new CreationResult(requiredOpen(bindingId), true);
        }
        return new CreationResult(requiredOpen(bindingId), false);
    }

    /** PENDING/到期 RETRY_WAIT 才能 claim；扫描重复只会让一个 Worker 的条件 UPDATE 成功。 */
    @Transactional
    public Optional<GitHubSyncRunEntity> claim(long runId) {
        Optional<GitHubSyncRunEntity> candidate = runMapper.findById(runId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        GitHubSyncRunEntity run = candidate.get();
        LocalDateTime startedAt = LocalDateTime.now(clock);
        if (runMapper.claim(run.id(), run.version(), startedAt) != 1) {
            return Optional.empty();
        }
        return runMapper.findById(runId);
    }

    /** Checkpoint 与 SUCCEEDED 在同一短事务中提交，任一 version 冲突都会整体回滚。 */
    @Transactional
    public void complete(
            GitHubSyncRunEntity run,
            GitHubSyncCheckpointEntity checkpoint,
            LocalDateTime successfulBoundary,
            String lastSeenCommitSha
    ) {
        checkpointService.completeWithinCurrentTransaction(
                checkpoint, successfulBoundary, lastSeenCommitSha
        );
        if (runMapper.markSucceeded(run.id(), run.version(), LocalDateTime.now(clock)) != 1) {
            throw new BusinessException(GitHubSyncErrorCode.SYNC_STATE_CONFLICT);
        }
    }

    /** 失败记录使用独立事务，不会随此前页面或业务事务的回滚而丢失。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GitHubSyncRunStatus> handleFailure(
            GitHubSyncRunEntity run,
            Classification failure
    ) {
        Instant now = clock.instant();
        int updated;
        GitHubSyncRunStatus result;
        if (retryPolicy.shouldRetry(failure.retryable(), run.attemptCount())) {
            Instant retryAt = retryPolicy.nextRetryAt(
                    run.attemptCount(), failure.retryAt(), now
            );
            updated = runMapper.markRetryWait(
                    run.id(), run.version(), LocalDateTime.ofInstant(retryAt, ZoneOffset.UTC),
                    failure.stableErrorCode(), failure.safeErrorMessage()
            );
            result = GitHubSyncRunStatus.RETRY_WAIT;
        } else {
            updated = runMapper.markDead(
                    run.id(), run.version(), LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                    failure.stableErrorCode(), failure.safeErrorMessage()
            );
            result = GitHubSyncRunStatus.DEAD;
        }
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }

    /** 崩溃遗留的超时 RUNNING 通过 startedAt + version 条件恢复，避免覆盖仍在工作的新版状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GitHubSyncRunStatus> recoverStale(
            GitHubSyncRunEntity run,
            LocalDateTime cutoff
    ) {
        Instant now = clock.instant();
        int updated;
        GitHubSyncRunStatus result;
        if (retryPolicy.shouldRetry(true, run.attemptCount())) {
            Instant retryAt = retryPolicy.nextRetryAt(run.attemptCount(), null, now);
            updated = runMapper.recoverStaleToRetryWait(
                    run.id(), run.version(), cutoff, LocalDateTime.ofInstant(retryAt, ZoneOffset.UTC)
            );
            result = GitHubSyncRunStatus.RETRY_WAIT;
        } else {
            updated = runMapper.recoverStaleToDead(
                    run.id(), run.version(), cutoff, LocalDateTime.ofInstant(now, ZoneOffset.UTC)
            );
            result = GitHubSyncRunStatus.DEAD;
        }
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }

    private GitHubSyncRunEntity requiredOpen(long bindingId) {
        return runMapper.findOpenCommitRun(bindingId)
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_STATE_CONFLICT));
    }

    public record CreationResult(GitHubSyncRunEntity run, boolean existing) {
    }
}
