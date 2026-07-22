package com.obdeadsoup.devpilot.project.persistence.entity;

import java.time.LocalDateTime;

public record ProjectActivityEntity(
        long id,
        long workspaceId,
        long projectId,
        Long githubRepositoryId,
        String repositoryFullName,
        String sourceType,
        String activityType,
        String sourceDeliveryId,
        Long externalActorId,
        String actorLogin,
        String gitRef,
        String beforeSha,
        String afterSha,
        Integer commitCount,
        String headCommitMessage,
        String title,
        String summary,
        String externalUrl,
        LocalDateTime occurredAt
) {
}
