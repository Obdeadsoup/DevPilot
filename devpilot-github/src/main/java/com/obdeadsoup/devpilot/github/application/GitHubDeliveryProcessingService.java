package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.parser.GitHubWebhookPayloadParser;
import com.obdeadsoup.devpilot.github.application.parser.GitHubWebhookProcessingPlan;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Delivery 成功处理事务：解析已入库 Payload、写原有聚合 Activity，并原子标记 SUCCEEDED。
 * Push Commit 明细先逐条调用统一 Upsert 的独立短事务；中途失败后重跑也由 Commit 唯一键去重。
 */
@Service
public class GitHubDeliveryProcessingService {

    private final GitHubWebhookPayloadParser payloadParser;
    private final ProjectActivityService activityService;
    private final GitHubCommitApplicationService commitApplicationService;
    private final GitHubDeliveryMapper deliveryMapper;
    private final Clock clock;

    public GitHubDeliveryProcessingService(
            GitHubWebhookPayloadParser payloadParser,
            ProjectActivityService activityService,
            GitHubCommitApplicationService commitApplicationService,
            GitHubDeliveryMapper deliveryMapper,
            Clock clock
    ) {
        this.payloadParser = payloadParser;
        this.activityService = activityService;
        this.commitApplicationService = commitApplicationService;
        this.deliveryMapper = deliveryMapper;
        this.clock = clock;
    }

    /**
     * 处理已被 Worker 抢占的 Delivery；Commit 与 Activity 唯一索引提供业务幂等，
     * version 条件确保只有当前抢占版本能提交 Delivery 成功状态。
     */
    @Transactional
    public void process(GitHubDeliveryEntity delivery) {
        GitHubWebhookProcessingPlan plan = payloadParser.parseForProcessing(delivery);
        plan.commits().forEach(commitApplicationService::upsert);
        activityService.recordGitHubActivity(plan.aggregateActivity());
        int updated = deliveryMapper.markSucceeded(delivery.id(), delivery.version(), LocalDateTime.now(clock));
        if (updated != 1) {
            throw new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
        }
    }
}
