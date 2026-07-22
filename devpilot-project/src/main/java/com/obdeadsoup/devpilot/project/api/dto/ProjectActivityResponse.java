package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.persistence.entity.ProjectActivityEntity;

import java.time.LocalDateTime;

public record ProjectActivityResponse(
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

    public static ProjectActivityResponse from(ProjectActivityEntity entity) {
        return new ProjectActivityResponse(
                entity.id(), entity.workspaceId(), entity.projectId(), entity.githubRepositoryId(),
                entity.repositoryFullName(), entity.sourceType(), entity.activityType(), entity.sourceDeliveryId(),
                entity.externalActorId(), entity.actorLogin(), entity.gitRef(), entity.beforeSha(), entity.afterSha(),
                entity.commitCount(), entity.headCommitMessage(), entity.title(), entity.summary(),
                entity.externalUrl(), entity.occurredAt()
        );
    }
}
