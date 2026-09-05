package com.obdeadsoup.devpilot.agent.persistence.entity;

import java.time.LocalDateTime;

public class AgentToolProposalEntity {
    private Long id;
    private String proposalId;
    private String runId;
    private long actorId;
    private long workspaceId;
    private long projectId;
    private String toolCallId;
    private String toolName;
    private String canonicalArguments;
    private String payloadHash;
    private String idempotencyKey;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime decisionAt;
    private LocalDateTime executedAt;
    private String executionResult;
    private String resourceId;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long version;

    public Long getId() { return id; } public void setId(Long v) { id=v; }
    public String getProposalId() { return proposalId; } public void setProposalId(String v) { proposalId=v; }
    public String getRunId() { return runId; } public void setRunId(String v) { runId=v; }
    public long getActorId() { return actorId; } public void setActorId(long v) { actorId=v; }
    public long getWorkspaceId() { return workspaceId; } public void setWorkspaceId(long v) { workspaceId=v; }
    public long getProjectId() { return projectId; } public void setProjectId(long v) { projectId=v; }
    public String getToolCallId() { return toolCallId; } public void setToolCallId(String v) { toolCallId=v; }
    public String getToolName() { return toolName; } public void setToolName(String v) { toolName=v; }
    public String getCanonicalArguments() { return canonicalArguments; } public void setCanonicalArguments(String v) { canonicalArguments=v; }
    public String getPayloadHash() { return payloadHash; } public void setPayloadHash(String v) { payloadHash=v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey=v; }
    public String getStatus() { return status; } public void setStatus(String v) { status=v; }
    public LocalDateTime getExpiresAt() { return expiresAt; } public void setExpiresAt(LocalDateTime v) { expiresAt=v; }
    public LocalDateTime getDecisionAt() { return decisionAt; } public void setDecisionAt(LocalDateTime v) { decisionAt=v; }
    public LocalDateTime getExecutedAt() { return executedAt; } public void setExecutedAt(LocalDateTime v) { executedAt=v; }
    public String getExecutionResult() { return executionResult; } public void setExecutionResult(String v) { executionResult=v; }
    public String getResourceId() { return resourceId; } public void setResourceId(String v) { resourceId=v; }
    public String getFailureReason() { return failureReason; } public void setFailureReason(String v) { failureReason=v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt=v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt=v; }
    public long getVersion() { return version; } public void setVersion(long v) { version=v; }
}
