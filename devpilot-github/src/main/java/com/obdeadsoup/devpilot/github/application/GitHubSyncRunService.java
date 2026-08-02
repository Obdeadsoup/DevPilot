package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSyncRunReceiptResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubSyncRunResponse;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Sync Run 的用户入口与扫描提交服务。Scheduler 扫描允许多实例看到相同候选，
 * Executor 只负责提交；Worker 真正运行时才由数据库 version claim 决定唯一执行者。
 * 因此线程池拒绝不会把尚未执行的 Run 错误标成 RUNNING。
 */
@Service
public class GitHubSyncRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubSyncRunService.class);

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService authorizationService;
    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubSyncCheckpointMapper checkpointMapper;
    private final GitHubSyncRunMapper runMapper;
    private final GitHubSyncRunStateService stateService;
    private final GitHubCommitReconciliationService reconciliationService;
    private final TaskExecutor taskExecutor;
    private final GitHubReconciliationProperties properties;
    private final Clock clock;

    public GitHubSyncRunService(
            CurrentUserProvider currentUserProvider,
            ProjectAuthorizationService authorizationService,
            GitHubRepositoryMapper repositoryMapper,
            GitHubSyncCheckpointMapper checkpointMapper,
            GitHubSyncRunMapper runMapper,
            GitHubSyncRunStateService stateService,
            GitHubCommitReconciliationService reconciliationService,
            @Qualifier("githubDeliveryTaskExecutor") TaskExecutor taskExecutor,
            GitHubReconciliationProperties properties,
            Clock clock
    ) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.repositoryMapper = repositoryMapper;
        this.checkpointMapper = checkpointMapper;
        this.runMapper = runMapper;
        this.stateService = stateService;
        this.reconciliationService = reconciliationService;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建 MANUAL Run 后立即异步提交；不接受用户 since，补偿边界始终由配置与 Checkpoint 决定。 */
    public GitHubSyncRunReceiptResponse requestManualCommitSync(
            long workspaceId,
            long projectId,
            long bindingId
    ) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.REPOSITORY_UPDATE
        );
        GitHubRepositoryEntity binding = repositoryMapper.findByScope(workspaceId, projectId, bindingId)
                .filter(entity -> "ACTIVE".equals(entity.bindingStatus()))
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_TARGET_UNAVAILABLE));
        GitHubSyncRunStateService.CreationResult creation = stateService.createOrGetOpen(
                binding.id(), GitHubSyncTriggerType.MANUAL, userId
        );
        submit(creation.run().id());
        return new GitHubSyncRunReceiptResponse(
                creation.run().id(), creation.run().status(), creation.existing()
        );
    }

    public GitHubSyncRunResponse getRun(
            long workspaceId,
            long projectId,
            long bindingId,
            long runId
    ) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.REPOSITORY_READ
        );
        repositoryMapper.findByScope(workspaceId, projectId, bindingId)
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_RUN_NOT_FOUND));
        GitHubSyncRunEntity run = runMapper.findById(runId)
                .filter(entity -> entity.repositoryBindingId() == bindingId)
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_RUN_NOT_FOUND));
        return GitHubSyncRunResponse.from(run);
    }

    /** 发现缺少开放 Run 的 ACTIVE Binding，再提交 PENDING/到期 RETRY_WAIT 候选。 */
    public void discoverAndSubmit() {
        int batchSize = properties.batchSize();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleCutoff = now.minus(properties.runTimeout());
        for (GitHubSyncRunEntity stale : runMapper.findStaleRunningCandidates(staleCutoff, batchSize)) {
            stateService.recoverStale(stale, staleCutoff);
        }
        for (long bindingId : repositoryMapper.findSyncEligibleBindingIds(batchSize)) {
            GitHubSyncTriggerType trigger = checkpointMapper.findCommitCheckpoint(bindingId)
                    .filter(checkpoint -> checkpoint.lastSuccessfulSyncAt() != null)
                    .map(ignored -> GitHubSyncTriggerType.SCHEDULED)
                    .orElse(GitHubSyncTriggerType.INITIAL);
            stateService.createOrGetOpen(bindingId, trigger, null);
        }
        for (long runId : runMapper.findRunnableCandidateIds(now, batchSize)) {
            submit(runId);
        }
    }

    private void submit(long runId) {
        try {
            taskExecutor.execute(() -> reconciliationService.reconcile(runId));
        } catch (TaskRejectedException exception) {
            LOGGER.warn(
                    "GitHub commit sync submission rejected runId={} exceptionType={}",
                    runId, exception.getClass().getName()
            );
        }
    }
}
