package com.obdeadsoup.devpilot.github.application.port;

public record PullRequestReviewState(long pullRequestId,long githubPullRequestId,int number,String status,
                                     boolean draft,String headSha,String htmlUrl,boolean hasCurrentHeadApproval,
                                     String latestReviewState,long workspaceId,long projectId) { }
