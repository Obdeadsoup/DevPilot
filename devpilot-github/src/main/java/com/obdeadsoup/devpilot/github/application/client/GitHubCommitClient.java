package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/**
 * Commit 对账的 GitHub API Client 边界。
 * 实现必须经过统一 HTTP Executor，并且只沿受信任 Link Cursor 翻页，调用凭据需要 Contents: read。
 */
public interface GitHubCommitClient {

    /**
     * 获取一页 Commit。cursor 有 next 时忽略 since/perPage 并沿 GitHub Link 继续；
     * 首次请求必须传空 Cursor，由 Client 生成受控 since 与 per_page 参数。
     */
    GitHubPage<GitHubCommit> listCommits(
            String owner,
            String repositoryName,
            Instant since,
            int perPage,
            String apiCredentialReference,
            GitHubPageCursor cursor
    );
}
