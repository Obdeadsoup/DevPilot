package com.obdeadsoup.devpilot.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.notification.domain.NotificationDedupeKeyFactory;
import com.obdeadsoup.devpilot.notification.domain.NotificationSourceType;
import com.obdeadsoup.devpilot.notification.domain.NotificationTargetType;
import com.obdeadsoup.devpilot.notification.domain.NotificationType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventHandler;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxHandleResult;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import com.obdeadsoup.devpilot.task.application.outbox.TaskInstantEventType;
import com.obdeadsoup.devpilot.task.application.outbox.TaskInstantNotificationPayloadV1;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把一个白名单 Task V1 事件转换为接收人维度的可靠 Notification。重复处理依赖
 * recipient + dedupeKey 唯一键收敛；本类不读取 Task 当前 Entity 推断历史事实。
 */
public class TaskNotificationOutboxHandler implements OutboxEventHandler {

    private final TaskInstantEventType eventType;
    private final ObjectMapper objectMapper;
    private final ProjectNotificationRecipientQuery recipientQuery;
    private final NotificationApplicationService notifications;
    private final NotificationDedupeKeyFactory dedupeKeys;

    public TaskNotificationOutboxHandler(
            TaskInstantEventType eventType,
            ObjectMapper objectMapper,
            ProjectNotificationRecipientQuery recipientQuery,
            NotificationApplicationService notifications,
            NotificationDedupeKeyFactory dedupeKeys) {
        this.eventType = eventType;
        this.objectMapper = objectMapper;
        this.recipientQuery = recipientQuery;
        this.notifications = notifications;
        this.dedupeKeys = dedupeKeys;
    }

    @Override
    public String supportedEventType() {
        return eventType.name();
    }

    @Override
    public int supportedSchemaVersion() {
        return 1;
    }

    @Override
    public OutboxHandleResult handle(OutboxEventEnvelope event) {
        TaskInstantNotificationPayloadV1 payload = deserialize(event);
        validate(event, payload);
        Set<Long> recipientIds = recipients(payload);
        if (recipientIds.isEmpty()) {
            throw invalid("Task event has no eligible recipient snapshot");
        }
        for (long recipientId : recipientIds) {
            notifications.createIfAbsent(command(payload, recipientId));
        }
        return OutboxHandleResult.PROCESSED;
    }

    private TaskInstantNotificationPayloadV1 deserialize(OutboxEventEnvelope event) {
        try {
            return objectMapper.treeToValue(event.payload(), TaskInstantNotificationPayloadV1.class);
        } catch (JsonProcessingException exception) {
            throw new OutboxProcessingException(
                    OutboxFailureType.MALFORMED_PAYLOAD, "Malformed task outbox payload");
        }
    }

    private void validate(OutboxEventEnvelope event, TaskInstantNotificationPayloadV1 payload) {
        if (payload == null
                || payload.workspaceId() <= 0
                || payload.projectId() <= 0
                || payload.taskId() <= 0
                || payload.taskVersion() < 0
                || payload.actorUserId() <= 0
                || payload.occurredAt() == null
                || payload.displayKey() == null
                || payload.safeTitleSnapshot() == null
                || event.aggregateId() != payload.taskId()
                || !"TASK".equals(event.aggregateType())) {
            throw invalid("Invalid task outbox payload");
        }
    }

    private Set<Long> recipients(TaskInstantNotificationPayloadV1 payload) {
        Set<Long> result = new LinkedHashSet<>();
        switch (eventType) {
            case TASK_ASSIGNED_V1, TASK_CHANGES_REQUESTED_V1 -> addRequired(result, payload.assigneeUserId());
            case TASK_UNASSIGNED_V1 -> addRequired(result, payload.previousAssigneeUserId());
            case TASK_SUBMITTED_FOR_REVIEW_V1 -> result.addAll(
                    recipientQuery.findManagerUserIds(payload.workspaceId(), payload.projectId()));
            case TASK_COMPLETED_V1, TASK_REOPENED_V1 -> {
                addOptional(result, payload.reporterUserId());
                addOptional(result, payload.assigneeUserId());
            }
        }
        return result;
    }

    private CreateNotificationCommand command(
            TaskInstantNotificationPayloadV1 payload, long recipientId) {
        String semantic = eventType.eventKeySuffix();
        return new CreateNotificationCommand(
                recipientId,
                payload.workspaceId(),
                payload.projectId(),
                notificationType(),
                title(),
                payload.displayKey() + " " + payload.safeTitleSnapshot(),
                NotificationTargetType.TASK,
                payload.taskId(),
                "/api/v1/workspaces/" + payload.workspaceId()
                        + "/projects/" + payload.projectId()
                        + "/tasks/" + payload.taskId(),
                NotificationSourceType.TASK,
                payload.taskId(),
                dedupeKeys.taskInstant(payload.taskId(), payload.taskVersion(), semantic),
                payload.occurredAt());
    }

    private NotificationType notificationType() {
        return switch (eventType) {
            case TASK_ASSIGNED_V1 -> NotificationType.TASK_ASSIGNED;
            case TASK_UNASSIGNED_V1 -> NotificationType.TASK_UNASSIGNED;
            case TASK_SUBMITTED_FOR_REVIEW_V1 -> NotificationType.TASK_SUBMITTED_FOR_REVIEW;
            case TASK_CHANGES_REQUESTED_V1 -> NotificationType.TASK_CHANGES_REQUESTED;
            case TASK_COMPLETED_V1 -> NotificationType.TASK_COMPLETED;
            case TASK_REOPENED_V1 -> NotificationType.TASK_REOPENED;
        };
    }

    private String title() {
        return switch (eventType) {
            case TASK_ASSIGNED_V1 -> "Task 已分配";
            case TASK_UNASSIGNED_V1 -> "Task 已取消分配";
            case TASK_SUBMITTED_FOR_REVIEW_V1 -> "Task 已提交 Review";
            case TASK_CHANGES_REQUESTED_V1 -> "Task 需要修改";
            case TASK_COMPLETED_V1 -> "Task 已完成";
            case TASK_REOPENED_V1 -> "Task 已重新打开";
        };
    }

    private void addRequired(Set<Long> recipients, Long userId) {
        if (userId == null || userId <= 0) {
            throw invalid("Task event required recipient is missing");
        }
        recipients.add(userId);
    }

    private void addOptional(Set<Long> recipients, Long userId) {
        if (userId != null && userId > 0) {
            recipients.add(userId);
        }
    }

    private OutboxProcessingException invalid(String message) {
        return new OutboxProcessingException(OutboxFailureType.INVALID_EVENT, message);
    }
}
