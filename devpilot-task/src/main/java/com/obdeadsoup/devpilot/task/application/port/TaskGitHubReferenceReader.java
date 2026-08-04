package com.obdeadsoup.devpilot.task.application.port;

/**
 * Task 到 GitHub 快照的防腐 Port。关联只接受本地 Snapshot ID 并由 Adapter 回填稳定 GitHub ID；
 * Task 永不依赖 GitHub 的 Entity、Mapper 或外部正文。
 */
public interface TaskGitHubReferenceReader {
    TaskExternalReferenceSnapshot readIssue(long workspaceId, long projectId, long issueSnapshotId);
    TaskExternalReferenceSnapshot readPullRequest(long workspaceId, long projectId, long pullRequestSnapshotId);
}
