package com.obdeadsoup.devpilot.github.application.client;

/** GitHub Pull Requests API 分页边界；响应 id 才是真正 github_pull_request_id。 */
public interface GitHubPullRequestClient {
    GitHubPage<GitHubPullRequest> listPullRequests(String owner,String repository,int perPage,
                                                   String credentialRef,GitHubPageCursor cursor);
}
