package com.obdeadsoup.devpilot.github.application;

import java.time.Instant;

public record GitHubBacklogSnapshot(
        long deliveryReceived,
        long deliveryRetryDue,
        long deliveryProcessing,
        long deliveryStaleProcessing,
        long deliveryOpenDead,
        double deliveryOldestReadyAgeSeconds,
        long syncPending,
        long syncRetryDue,
        long syncRunning,
        long syncStaleRunning,
        long syncOpenDead,
        double syncOldestReadyAgeSeconds,
        double syncOldestRunningAgeSeconds,
        Instant lastUpdatedAt) {

    public static GitHubBacklogSnapshot empty() {
        return new GitHubBacklogSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }
}
