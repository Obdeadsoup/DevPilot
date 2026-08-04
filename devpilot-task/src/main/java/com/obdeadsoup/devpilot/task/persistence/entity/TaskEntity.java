package com.obdeadsoup.devpilot.task.persistence.entity;

import java.time.LocalDateTime;

public class TaskEntity {
    private Long id; private long workspaceId; private long projectId; private String title; private String description;
    private String status; private String priority; private long reporterUserId; private Long assigneeUserId;
    private LocalDateTime dueAt; private LocalDateTime completedAt; private LocalDateTime canceledAt;
    private LocalDateTime createdAt; private LocalDateTime updatedAt; private long version; private boolean deleted;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public long getWorkspaceId() { return workspaceId; } public void setWorkspaceId(long v) { workspaceId = v; }
    public long getProjectId() { return projectId; } public void setProjectId(long v) { projectId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { title = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getPriority() { return priority; } public void setPriority(String v) { priority = v; }
    public long getReporterUserId() { return reporterUserId; } public void setReporterUserId(long v) { reporterUserId = v; }
    public Long getAssigneeUserId() { return assigneeUserId; } public void setAssigneeUserId(Long v) { assigneeUserId = v; }
    public LocalDateTime getDueAt() { return dueAt; } public void setDueAt(LocalDateTime v) { dueAt = v; }
    public LocalDateTime getCompletedAt() { return completedAt; } public void setCompletedAt(LocalDateTime v) { completedAt = v; }
    public LocalDateTime getCanceledAt() { return canceledAt; } public void setCanceledAt(LocalDateTime v) { canceledAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
    public long getVersion() { return version; } public void setVersion(long v) { version = v; }
    public boolean isDeleted() { return deleted; } public void setDeleted(boolean v) { deleted = v; }
}
