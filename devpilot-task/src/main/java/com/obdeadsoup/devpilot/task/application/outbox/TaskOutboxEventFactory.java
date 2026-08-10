package com.obdeadsoup.devpilot.task.application.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.outbox.application.OutboxEventPublisher;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.task.domain.TaskAction;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 把 Task 受控写入转换为确定性、版本化 Outbox 事件。调用点位于 Task 原事务内，
 * 因此 Outbox INSERT 失败会连同 Task、History 和 Activity 一起回滚。
 */
@Component
public class TaskOutboxEventFactory {

    private static final int SCHEMA_VERSION = 1;

    private final OutboxEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public TaskOutboxEventFactory(OutboxEventPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    public void publishAssigned(
            TaskEntity task,
            String projectKey,
            long taskVersion,
            long actorUserId,
            long assigneeUserId,
            LocalDateTime occurredAt) {
        publish(task, projectKey, taskVersion, actorUserId, occurredAt,
                TaskInstantEventType.TASK_ASSIGNED_V1, assigneeUserId, null, null);
    }

    public void publishUnassigned(
            TaskEntity task,
            String projectKey,
            long taskVersion,
            long actorUserId,
            long previousAssigneeUserId,
            LocalDateTime occurredAt) {
        publish(task, projectKey, taskVersion, actorUserId, occurredAt,
                TaskInstantEventType.TASK_UNASSIGNED_V1, null, previousAssigneeUserId, null);
    }

    public void publishWorkflow(
            TaskEntity task,
            String projectKey,
            long taskVersion,
            long actorUserId,
            TaskAction action,
            LocalDateTime occurredAt) {
        TaskInstantEventType type = switch (action) {
            case SUBMITTED_FOR_REVIEW -> TaskInstantEventType.TASK_SUBMITTED_FOR_REVIEW_V1;
            case CHANGES_REQUESTED -> TaskInstantEventType.TASK_CHANGES_REQUESTED_V1;
            case COMPLETED -> TaskInstantEventType.TASK_COMPLETED_V1;
            case REOPENED -> TaskInstantEventType.TASK_REOPENED_V1;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Long assignee = switch (type) {
            case TASK_SUBMITTED_FOR_REVIEW_V1, TASK_CHANGES_REQUESTED_V1,
                    TASK_COMPLETED_V1, TASK_REOPENED_V1 -> task.getAssigneeUserId();
            default -> null;
        };
        Long reporter = switch (type) {
            case TASK_COMPLETED_V1, TASK_REOPENED_V1 -> task.getReporterUserId();
            default -> null;
        };
        publish(task, projectKey, taskVersion, actorUserId, occurredAt, type, assignee, null, reporter);
    }

    private void publish(
            TaskEntity task,
            String projectKey,
            long taskVersion,
            long actorUserId,
            LocalDateTime occurredAt,
            TaskInstantEventType type,
            Long assigneeUserId,
            Long previousAssigneeUserId,
            Long reporterUserId) {
        long taskId = task.getId();
        TaskInstantNotificationPayloadV1 payload = new TaskInstantNotificationPayloadV1(
                task.getWorkspaceId(),
                task.getProjectId(),
                taskId,
                taskVersion,
                projectKey + "-" + taskId,
                safeTitle(task.getTitle()),
                actorUserId,
                occurredAt,
                assigneeUserId,
                previousAssigneeUserId,
                reporterUserId);
        JsonNode payloadTree = objectMapper.valueToTree(payload);
        publisher.publish(new OutboxEventEnvelope(
                eventKey(taskId, taskVersion, type),
                "TASK",
                taskId,
                type.name(),
                SCHEMA_VERSION,
                payloadTree,
                occurredAt));
    }

    public String eventKey(long taskId, long taskVersion, TaskInstantEventType type) {
        return "task:" + taskId + ":v" + taskVersion + ":" + type.eventKeySuffix();
    }

    private String safeTitle(String title) {
        String sanitized = title == null ? "Task" : title.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (sanitized.isBlank()) {
            sanitized = "Task";
        }
        return sanitized.substring(0, Math.min(200, sanitized.length()));
    }
}
