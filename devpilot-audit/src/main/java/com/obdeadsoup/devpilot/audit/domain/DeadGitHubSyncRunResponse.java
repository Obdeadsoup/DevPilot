package com.obdeadsoup.devpilot.audit.domain;

import java.time.LocalDateTime;

public record DeadGitHubSyncRunResponse(
        long id, long bindingId, String resourceType, String triggerType, String status,
        int attemptCount, LocalDateTime completedAt, String lastErrorCode, String lastErrorMessage,
        int replayCount, Long replayOfRunId, long version) {
}
