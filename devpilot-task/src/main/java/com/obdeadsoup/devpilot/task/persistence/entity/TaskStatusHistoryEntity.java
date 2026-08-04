package com.obdeadsoup.devpilot.task.persistence.entity;

import java.time.LocalDateTime;

public record TaskStatusHistoryEntity(long id, long workspaceId, long projectId, long taskId, String fromStatus,
                                      String toStatus, String action, long actorUserId, String reason,
                                      long taskVersion, LocalDateTime occurredAt) { }
