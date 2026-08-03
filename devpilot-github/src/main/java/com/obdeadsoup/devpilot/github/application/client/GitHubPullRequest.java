package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

public record GitHubPullRequest(long githubPullRequestId,int number,String title,String body,String state,
                                boolean draft,Long authorGitHubUserId,String authorLogin,String headRef,
                                String headSha,String baseRef,String baseSha,String mergeCommitSha,
                                String requestedReviewersJson,String assigneesJson,String labelsJson,
                                String htmlUrl,Instant createdAt,Instant updatedAt,Instant closedAt,Instant mergedAt){}
