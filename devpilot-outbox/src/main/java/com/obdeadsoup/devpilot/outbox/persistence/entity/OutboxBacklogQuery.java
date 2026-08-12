package com.obdeadsoup.devpilot.outbox.persistence.entity;

import java.time.LocalDateTime;

public class OutboxBacklogQuery {
    private long pendingCount;
    private long retryWaitDueCount;
    private long processingCount;
    private long staleProcessingCount;
    private long openDeadCount;
    private LocalDateTime oldestReadyAt;

    public long getPendingCount() { return pendingCount; }
    public void setPendingCount(long value) { pendingCount = value; }
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
}
