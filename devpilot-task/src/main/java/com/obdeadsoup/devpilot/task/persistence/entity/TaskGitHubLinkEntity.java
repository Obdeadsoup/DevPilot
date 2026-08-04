package com.obdeadsoup.devpilot.task.persistence.entity;

import java.time.LocalDateTime;

public record TaskGitHubLinkEntity(long id, long workspaceId, long projectId, long taskId,
        long repositoryBindingId, long githubRepositoryId, String resourceType, String relationType,
        Long issueSnapshotId, Long pullRequestSnapshotId, long githubObjectId, int githubNumber,
        String linkStatus, long createdBy, Long removedBy, LocalDateTime createdAt, LocalDateTime removedAt,
        long version) { }
