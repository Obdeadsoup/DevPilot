package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/** GitHub List Commits 响应中允许进入本地模型的有限字段。 */
public record GitHubCommit(
        String sha,
        String message,
        String authorName,
        String authorEmail,
        Long authorGitHubUserId,
        String authorLogin,
        Instant authoredAt,
        Instant committedAt,
        String htmlUrl
) {
}
