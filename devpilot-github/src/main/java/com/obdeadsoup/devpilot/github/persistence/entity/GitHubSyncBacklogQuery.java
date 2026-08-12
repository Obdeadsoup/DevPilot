package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public class GitHubSyncBacklogQuery {
    private long pendingCount;
    private long retryWaitDueCount;
    private long runningCount;
    private long staleRunningCount;
    private long openDeadCount;
    private LocalDateTime oldestReadyAt;
    private LocalDateTime oldestRunningAt;

    public long getPendingCount() { return pendingCount; }
    public void setPendingCount(long value) { pendingCount = value; }
    public long getRetryWaitDueCount() { return retryWaitDueCount; }
    public void setRetryWaitDueCount(long value) { retryWaitDueCount = value; }
    public long getRunningCount() { return runningCount; }
    public void setRunningCount(long value) { runningCount = value; }
    public long getStaleRunningCount() { return staleRunningCount; }
    public void setStaleRunningCount(long value) { staleRunningCount = value; }
    public long getOpenDeadCount() { return openDeadCount; }
    public void setOpenDeadCount(long value) { openDeadCount = value; }
    public LocalDateTime getOldestReadyAt() { return oldestReadyAt; }
    public void setOldestReadyAt(LocalDateTime value) { oldestReadyAt = value; }
    public LocalDateTime getOldestRunningAt() { return oldestRunningAt; }
    public void setOldestRunningAt(LocalDateTime value) { oldestRunningAt = value; }
}
