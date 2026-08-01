package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Webhook Delivery 的崩溃恢复 Application Service。
 * 先回收超时 PROCESSING，再提交 RECEIVED 与到期 RETRY_WAIT；条件 UPDATE 负责并发仲裁。
 */
@Service
public class GitHubDeliveryRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubDeliveryRecoveryService.class);

    private final GitHubDeliveryMapper deliveryMapper;
    private final GitHubDeliveryStateService stateService;
    private final GitHubDeliveryWorker worker;
    private final TaskExecutor taskExecutor;
    private final GitHubIntegrationProperties properties;
    private final Clock clock;

    public GitHubDeliveryRecoveryService(
            GitHubDeliveryMapper deliveryMapper,
            GitHubDeliveryStateService stateService,
            GitHubDeliveryWorker worker,
            @Qualifier("githubDeliveryTaskExecutor") TaskExecutor taskExecutor,
            GitHubIntegrationProperties properties,
            Clock clock
    ) {
        this.deliveryMapper = deliveryMapper;
        this.stateService = stateService;
        this.worker = worker;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 扫描一个受配置限制的恢复批次并异步提交；重复 Scheduler/实例可能读到相同候选，
     * Worker 的 version 条件抢占保证只有一个处理者成功。
     */
    public void recover() {
        LocalDateTime now = LocalDateTime.now(clock);
        int batchSize = properties.deliveryRecoveryBatchSize();
        LocalDateTime cutoff = now.minus(properties.deliveryProcessingTimeout());

        List<GitHubDeliveryEntity> staleProcessing =
                deliveryMapper.findStaleProcessingCandidates(cutoff, batchSize);
        for (GitHubDeliveryEntity delivery : staleProcessing) {
            stateService.recoverStaleProcessing(delivery, cutoff);
        }

        Set<Long> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(deliveryMapper.findReceivedCandidateIds(batchSize));
        candidateIds.addAll(deliveryMapper.findDueRetryCandidateIds(now, batchSize));
        for (long deliveryId : candidateIds) {
            submit(deliveryId);
        }
    }

    private void submit(long deliveryId) {
        try {
            taskExecutor.execute(() -> worker.process(deliveryId));
        } catch (TaskRejectedException exception) {
            LOGGER.warn(
                    "GitHub delivery recovery submission rejected deliveryId={} exceptionType={}",
                    deliveryId,
                    exception.getClass().getName()
            );
        }
    }
}
