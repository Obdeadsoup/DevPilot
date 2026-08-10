package com.obdeadsoup.devpilot.task.application.outbox;

import java.time.LocalDateTime;

/**
 * Task 即时通知的 V1 最小事实快照。只保存接收人判定必需的本地 ID 和安全标题，
 * 不保存 description、GitHub Body、凭据、Entity 或 Authentication。
 */
public record TaskInstantNotificationPayloadV1(
        long workspaceId,
        long projectId,
        long taskId,
        long taskVersion,
        String displayKey,
        String safeTitleSnapshot,
        long actorUserId,
        LocalDateTime occurredAt,
        Long assigneeUserId,
        Long previousAssigneeUserId,
        Long reporterUserId) {
}
