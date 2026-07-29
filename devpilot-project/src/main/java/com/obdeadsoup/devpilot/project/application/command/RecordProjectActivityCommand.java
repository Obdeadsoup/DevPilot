package com.obdeadsoup.devpilot.project.application.command;

import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Records external activity metadata. {@code externalActorId} and {@code actorLogin} describe the
 * GitHub sender only; they must never be used to construct a local Authentication or grant a
 * DevPilot role.
 */
public record RecordProjectActivityCommand(
        long workspaceId,
        long projectId,
        Long githubRepositoryId,
        String repositoryFullName,
        ProjectActivitySourceType sourceType,
        ProjectActivityType activityType,
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

    public RecordProjectActivityCommand {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(activityType, "activityType must not be null");
        Objects.requireNonNull(sourceDeliveryId, "sourceDeliveryId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
