package com.obdeadsoup.devpilot.task.application.port;

import com.obdeadsoup.devpilot.task.domain.TaskGitHubResourceType;

/** Task 侧只保留建立关联所需的安全快照，不泄漏 GitHub Entity、正文或 Mapper。 */
public record TaskExternalReferenceSnapshot(
        long localSnapshotId, TaskGitHubResourceType resourceType, long repositoryBindingId,
        long githubRepositoryId, long githubObjectId, int githubNumber, String title,
        String currentState, String htmlUrl, long workspaceId, long projectId
) {
}
