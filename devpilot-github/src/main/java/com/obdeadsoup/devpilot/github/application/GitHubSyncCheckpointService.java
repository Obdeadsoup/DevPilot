package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Commit Checkpoint 的短事务服务。初次使用受限 Lookback，后续从可靠边界减去 overlapWindow；
 * 重叠会重复读取，但统一 Upsert 保证不会重复 Commit 或 Activity。
 */
@Service
public class GitHubSyncCheckpointService {

    private final GitHubSyncCheckpointMapper checkpointMapper;
    private final GitHubReconciliationProperties properties;

    public GitHubSyncCheckpointService(
            GitHubSyncCheckpointMapper checkpointMapper,
            GitHubReconciliationProperties properties
    ) {
        this.checkpointMapper = checkpointMapper;
        this.properties = properties;
    }

    @Transactional
    public GitHubSyncCheckpointEntity getOrCreate(long bindingId) {
        checkpointMapper.insertIfAbsent(bindingId, properties.overlapWindow().toSeconds());
        return findRequired(bindingId);
    }

    public Instant calculateSince(GitHubSyncCheckpointEntity checkpoint, Instant now) {
        if (checkpoint.lastSuccessfulSyncAt() == null) {
            return now.minus(properties.initialLookback());
        }
        return checkpoint.lastSuccessfulSyncAt().toInstant(ZoneOffset.UTC)
                .minusSeconds(checkpoint.overlapSeconds());
    }

    /** 当前页全部 Commit 已分别提交后，才记录非可靠的页级 lastSeen SHA。 */
    @Transactional
    public GitHubSyncCheckpointEntity recordPage(
            GitHubSyncCheckpointEntity checkpoint,
            String lastSeenCommitSha
    ) {
        if (lastSeenCommitSha == null) {
            return checkpoint;
        }
        int updated = checkpointMapper.updatePageProgress(
                checkpoint.id(), checkpoint.version(), lastSeenCommitSha
        );
        if (updated != 1) {
            throw new BusinessException(GitHubSyncErrorCode.CHECKPOINT_CONFLICT);
        }
        return findRequired(checkpoint.repositoryBindingId());
    }

    /**
     * 由 Sync Run 成功事务调用；Checkpoint 必须先成功推进，随后同事务把 Run 标记成功。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeWithinCurrentTransaction(
            GitHubSyncCheckpointEntity checkpoint,
            LocalDateTime successfulBoundary,
            String lastSeenCommitSha
    ) {
        int updated = checkpointMapper.complete(
                checkpoint.id(), checkpoint.version(), successfulBoundary, lastSeenCommitSha,
                properties.overlapWindow().toSeconds()
        );
        if (updated != 1) {
            throw new BusinessException(GitHubSyncErrorCode.CHECKPOINT_CONFLICT);
        }
    }

    private GitHubSyncCheckpointEntity findRequired(long bindingId) {
        return checkpointMapper.findCommitCheckpoint(bindingId)
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.CHECKPOINT_CONFLICT));
    }
}
