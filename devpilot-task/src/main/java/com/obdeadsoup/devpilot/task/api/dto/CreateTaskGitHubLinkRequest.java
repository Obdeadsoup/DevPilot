package com.obdeadsoup.devpilot.task.api.dto;
import com.obdeadsoup.devpilot.task.domain.*; import jakarta.validation.constraints.Min; import jakarta.validation.constraints.NotNull; import jakarta.validation.constraints.Positive;
public record CreateTaskGitHubLinkRequest(@NotNull TaskGitHubResourceType resourceType,@Positive long snapshotId,TaskGitHubRelationType relationType,@Min(0) long expectedTaskVersion) { }
