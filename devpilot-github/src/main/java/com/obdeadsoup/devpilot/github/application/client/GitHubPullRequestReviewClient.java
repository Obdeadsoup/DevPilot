package com.obdeadsoup.devpilot.github.application.client;

/**
 * 单个近期活跃 PR 的 Reviews API 分页边界。调用方先做有界 PR 候选筛选，
 * 避免每轮对全部历史 PR 形成无限 N+1。
 */
public interface GitHubPullRequestReviewClient {
    GitHubPage<GitHubPullRequestReview> listReviews(String owner,String repository,int pullRequestNumber,
                                                    int perPage,String credentialRef,GitHubPageCursor cursor);
}
