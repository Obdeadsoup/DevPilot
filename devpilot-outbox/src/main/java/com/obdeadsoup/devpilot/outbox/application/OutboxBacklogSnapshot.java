package com.obdeadsoup.devpilot.outbox.application;

import java.time.Instant;

public record OutboxBacklogSnapshot(
        long pending,
        long retryDue,
        long processing,
        long staleProcessing,
        long openDead,
        double oldestReadyAgeSeconds,
        Instant lastUpdatedAt) {
    public static OutboxBacklogSnapshot empty() {
        return new OutboxBacklogSnapshot(0, 0, 0, 0, 0, 0, null);
    }
}
