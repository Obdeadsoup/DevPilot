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
 * Delivery 成功处理事务：解析已入库 Payload，调用 Commit/Issue/PR/Review 统一 Upsert，
 * 必要时写聚合 Activity，并原子标记 SUCCEEDED。业务短事务与唯一键使失败重跑保持幂等。
 */
@Service
public class GitHubDeliveryProcessingService {

    private final GitHubWebhookPayloadParser payloadParser;
    private final ProjectActivityService activityService;
    private final GitHubCommitApplicationService commitApplicationService;
    private final GitHubIssueApplicationService issueApplicationService;
    private final GitHubPullRequestApplicationService pullRequestApplicationService;
    private final GitHubPullRequestReviewApplicationService reviewApplicationService;
    private final com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper repositoryMapper;
    private final GitHubDeliveryMapper deliveryMapper;
    private final Clock clock;

    public GitHubDeliveryProcessingService(
            GitHubWebhookPayloadParser payloadParser,
            ProjectActivityService activityService,
            GitHubCommitApplicationService commitApplicationService,
            GitHubIssueApplicationService issueApplicationService,
            GitHubPullRequestApplicationService pullRequestApplicationService,
            GitHubPullRequestReviewApplicationService reviewApplicationService,
            com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper repositoryMapper,
            GitHubDeliveryMapper deliveryMapper,
            Clock clock
    ) {
        this.payloadParser = payloadParser;
        this.activityService = activityService;
        this.commitApplicationService = commitApplicationService;
        this.issueApplicationService = issueApplicationService;
        this.pullRequestApplicationService = pullRequestApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.repositoryMapper = repositoryMapper;
        this.deliveryMapper = deliveryMapper;
        this.clock = clock;
    }

    /**
     * 处理已被 Worker 抢占的 Delivery；Commit 与 Activity 唯一索引提供业务幂等，
     * version 条件确保只有当前抢占版本能提交 Delivery 成功状态。
     */
    @Transactional
    public void process(GitHubDeliveryEntity delivery) {
        var binding = repositoryMapper.findByScope(delivery.workspaceId(), delivery.projectId(), delivery.repositoryId())
                .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.REPOSITORY_NOT_FOUND));
        GitHubWebhookProcessingPlan plan = payloadParser.parseForProcessing(delivery, binding);
        plan.commits().forEach(commitApplicationService::upsert);
        plan.issues().forEach(issueApplicationService::upsertIssue);
        plan.pullRequests().forEach(pullRequestApplicationService::upsertPullRequest);
        plan.reviews().forEach(reviewApplicationService::upsertReview);
        if (plan.aggregateActivity() != null) {
            activityService.recordGitHubActivity(plan.aggregateActivity());
        }
        int updated = deliveryMapper.markSucceeded(delivery.id(), delivery.version(), LocalDateTime.now(clock));
        if (updated != 1) {
            throw new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
        }
    }
}
