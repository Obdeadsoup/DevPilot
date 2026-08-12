package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public class GitHubDeliveryBacklogQuery {
    private long receivedCount;
    private long retryWaitDueCount;
    private long processingCount;
    private long staleProcessingCount;
    private long openDeadCount;
    private LocalDateTime oldestReadyAt;
    private LocalDateTime oldestProcessingAt;

    public long getReceivedCount() { return receivedCount; }
    public void setReceivedCount(long value) { receivedCount = value; }
    public long getRetryWaitDueCount() { return retryWaitDueCount; }
    public void setRetryWaitDueCount(long value) { retryWaitDueCount = value; }
    public long getProcessingCount() { return processingCount; }
    public void setProcessingCount(long value) { processingCount = value; }
    public long getStaleProcessingCount() { return staleProcessingCount; }
    public void setStaleProcessingCount(long value) { staleProcessingCount = value; }
    public long getOpenDeadCount() { return openDeadCount; }
    public void setOpenDeadCount(long value) { openDeadCount = value; }
    public LocalDateTime getOldestReadyAt() { return oldestReadyAt; }
    public void setOldestReadyAt(LocalDateTime value) { oldestReadyAt = value; }
    public LocalDateTime getOldestProcessingAt() { return oldestProcessingAt; }
    public void setOldestProcessingAt(LocalDateTime value) { oldestProcessingAt = value; }
}
