package com.obdeadsoup.devpilot.task.application.port;

import java.time.LocalDateTime;

public record TaskReminderCandidate(long taskId, long workspaceId, long projectId, String displayKey,
                                    String title, String status, long reporterUserId, Long assigneeUserId,
                                    LocalDateTime dueAt, LocalDateTime submittedForReviewAt, long taskVersion) { }
