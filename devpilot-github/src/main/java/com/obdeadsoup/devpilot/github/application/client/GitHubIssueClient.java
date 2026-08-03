package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/**
 * GitHub Issues API 边界。该 API 同时返回 PR，因此实现必须过滤带 pull_request 字段的条目，
 * 不能把 Issue 响应 id 当作 GitHub Pull Request ID。
 */
public interface GitHubIssueClient {
    GitHubPage<GitHubIssue> listIssues(String owner,String repository,Instant since,int perPage,
                                       String credentialRef,GitHubPageCursor cursor);
}
