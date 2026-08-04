package com.obdeadsoup.devpilot.task.api.dto;
import com.obdeadsoup.devpilot.task.domain.*;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskGitHubLinkEntity;
import java.time.LocalDateTime;
public record TaskGitHubLinkResponse(long id,long repositoryBindingId,long githubRepositoryId,TaskGitHubResourceType resourceType,
        TaskGitHubRelationType relationType,Long issueSnapshotId,Long pullRequestSnapshotId,long githubObjectId,int githubNumber,
        TaskGitHubLinkStatus linkStatus,long createdBy,Long removedBy,LocalDateTime createdAt,LocalDateTime removedAt,long version){
    public static TaskGitHubLinkResponse from(TaskGitHubLinkEntity link){return new TaskGitHubLinkResponse(link.id(),link.repositoryBindingId(),link.githubRepositoryId(),TaskGitHubResourceType.valueOf(link.resourceType()),TaskGitHubRelationType.valueOf(link.relationType()),link.issueSnapshotId(),link.pullRequestSnapshotId(),link.githubObjectId(),link.githubNumber(),TaskGitHubLinkStatus.valueOf(link.linkStatus()),link.createdBy(),link.removedBy(),link.createdAt(),link.removedAt(),link.version());}
}
