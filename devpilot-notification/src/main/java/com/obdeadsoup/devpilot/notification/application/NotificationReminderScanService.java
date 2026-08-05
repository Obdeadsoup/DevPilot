package com.obdeadsoup.devpilot.notification.application;

import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewState;
import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewStateReader;
import com.obdeadsoup.devpilot.notification.config.NotificationReminderProperties;
import com.obdeadsoup.devpilot.notification.domain.NotificationDedupeKeyFactory;
import com.obdeadsoup.devpilot.notification.domain.NotificationSourceType;
import com.obdeadsoup.devpilot.notification.domain.NotificationTargetType;
import com.obdeadsoup.devpilot.notification.domain.NotificationType;
import com.obdeadsoup.devpilot.task.application.port.TaskReminderCandidate;
import com.obdeadsoup.devpilot.task.application.port.TaskReminderCandidateReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 编排五类扫描规则。扫描天然会重复发现候选，稳定 dedupeKey 与数据库唯一索引共同提供多实例幂等；
 * 每条 createIfAbsent 是独立短事务，不把整轮扫描包进事务。
 */
@Service
public class NotificationReminderScanService {

    private static final Logger log = LoggerFactory.getLogger(NotificationReminderScanService.class);

    private final TaskReminderCandidateReader tasks;
    private final PullRequestReviewStateReader pullRequests;
    private final NotificationApplicationService notifications;
    private final NotificationDedupeKeyFactory keys;
    private final NotificationRecipientResolver recipients;
    private final NotificationReminderProperties properties;
    private final Clock clock;

    public NotificationReminderScanService(
            TaskReminderCandidateReader tasks,
            PullRequestReviewStateReader pullRequests,
            NotificationApplicationService notifications,
            NotificationDedupeKeyFactory keys,
            NotificationRecipientResolver recipients,
            NotificationReminderProperties properties,
            Clock clock) {
        this.tasks = tasks;
        this.pullRequests = pullRequests;
        this.notifications = notifications;
        this.keys = keys;
        this.recipients = recipients;
        this.properties = properties;
        this.clock = clock;
    }

    /** 一类规则失败不会阻止其他规则；fixedDelay 的下一轮只在本轮完成后计算。 */
    public void scan() {
        LocalDateTime now = LocalDateTime.now(clock);
        safelyRunRule("due-soon", () -> scanDueSoon(now));
        safelyRunRule("overdue", () -> scanOverdue(now));
        safelyRunRule("escalation", () -> scanEscalations(now));
        safelyRunRule("task-review", () -> scanTaskReviewTimeouts(now));
        safelyRunRule("pr-review", () -> scanPullRequestReviewTimeouts(now));
    }

    private void scanDueSoon(LocalDateTime now) {
        forEachCandidate(
                tasks.findDueSoon(now, now.plus(properties.taskDueSoonWindow()), properties.batchSize()),
                task -> createTaskNotification(
                        task,
                        recipients.assigneeOrReporter(task.assigneeUserId(), task.reporterUserId()),
                        NotificationType.TASK_DUE_SOON,
                        keys.taskDueSoon(task.taskId(), task.dueAt()),
                        "Task 即将到期"));
    }

    private void scanOverdue(LocalDateTime now) {
        forEachCandidate(
                tasks.findOverdue(now, properties.batchSize()),
                task -> createTaskNotification(
                        task,
                        recipients.assigneeOrReporter(task.assigneeUserId(), task.reporterUserId()),
                        NotificationType.TASK_OVERDUE,
                        keys.taskOverdue(task.taskId(), task.dueAt()),
                        "Task 已逾期"));
    }

    private void scanEscalations(LocalDateTime now) {
        LocalDateTime threshold = now.minus(properties.taskOverdueEscalationDelay());
        forEachCandidate(
                tasks.findOverdueForEscalation(threshold, properties.batchSize()),
                task -> recipients.managers(task.workspaceId(), task.projectId()).forEach(recipientId ->
                        createTaskNotification(
                                task,
                                recipientId,
                                NotificationType.TASK_OVERDUE_ESCALATED,
                                keys.taskOverdueEscalated(task.taskId(), task.dueAt()),
                                "Task 逾期升级")));
    }

    private void scanTaskReviewTimeouts(LocalDateTime now) {
        LocalDateTime threshold = now.minus(properties.taskReviewTimeout());
        forEachCandidate(
                tasks.findReviewTimeout(threshold, properties.batchSize()),
                task -> recipients.assigneeAndManagers(
                                task.assigneeUserId(), task.workspaceId(), task.projectId())
                        .forEach(recipientId -> createTaskNotification(
                                task,
                                recipientId,
                                NotificationType.TASK_REVIEW_TIMEOUT,
                                keys.taskReviewTimeout(task.taskId(), task.submittedForReviewAt()),
                                "Task Review 超时")));
    }

    private void scanPullRequestReviewTimeouts(LocalDateTime now) {
        LocalDateTime threshold = now.minus(properties.pullRequestReviewTimeout());
        forEachCandidate(
                tasks.findReviewTimeout(threshold, properties.batchSize()),
                task -> pullRequests.findForTask(task.workspaceId(), task.projectId(), task.taskId())
                        .filter(pullRequest -> "OPEN".equals(pullRequest.status()))
                        .filter(pullRequest -> !pullRequest.draft())
                        .filter(pullRequest -> !pullRequest.hasCurrentHeadApproval())
                        .ifPresent(pullRequest -> recipients.assigneeAndManagers(
                                        task.assigneeUserId(), task.workspaceId(), task.projectId())
                                .forEach(recipientId -> createPullRequestNotification(
                                        task, pullRequest, recipientId))));
    }

    private void createTaskNotification(
            TaskReminderCandidate task,
            long recipientId,
            NotificationType type,
            String dedupeKey,
            String title) {
        notifications.createIfAbsent(new CreateNotificationCommand(
                recipientId,
                task.workspaceId(),
                task.projectId(),
                type,
                title,
                safeTitle(task.title()),
                NotificationTargetType.TASK,
                task.taskId(),
                taskPath(task),
                NotificationSourceType.TASK,
                task.taskId(),
                dedupeKey,
                LocalDateTime.now(clock)));
    }

    private void createPullRequestNotification(
            TaskReminderCandidate task, PullRequestReviewState pullRequest, long recipientId) {
        notifications.createIfAbsent(new CreateNotificationCommand(
                recipientId,
                task.workspaceId(),
                task.projectId(),
                NotificationType.PULL_REQUEST_REVIEW_TIMEOUT,
                "Pull Request Review 超时",
                safeTitle(task.title()),
                NotificationTargetType.PULL_REQUEST,
                pullRequest.pullRequestId(),
                "/api/v1/workspaces/" + task.workspaceId()
                        + "/projects/" + task.projectId()
                        + "/github/pull-requests/" + pullRequest.pullRequestId(),
                NotificationSourceType.GITHUB_PULL_REQUEST,
                pullRequest.pullRequestId(),
                keys.pullRequestReviewTimeout(
                        pullRequest.pullRequestId(), pullRequest.headSha(), task.submittedForReviewAt()),
                LocalDateTime.now(clock)));
    }

    private String taskPath(TaskReminderCandidate task) {
        return "/api/v1/workspaces/" + task.workspaceId()
                + "/projects/" + task.projectId()
                + "/tasks/" + task.taskId();
    }

    private String safeTitle(String title) {
        String sanitized = title == null ? "Task" : title.replaceAll("[\\p{Cntrl}]", " ").strip();
        return sanitized.substring(0, Math.min(200, sanitized.length()));
    }

    private void forEachCandidate(
            List<TaskReminderCandidate> candidates, Consumer<TaskReminderCandidate> consumer) {
        for (TaskReminderCandidate candidate : candidates) {
            try {
                consumer.accept(candidate);
            } catch (RuntimeException exception) {
                log.warn(
                        "Notification candidate failed ruleItemTaskId={} failureType={}",
                        candidate.taskId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void safelyRunRule(String rule, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn(
                    "Notification rule failed rule={} failureType={}",
                    rule,
                    exception.getClass().getSimpleName());
        }
    }
}
