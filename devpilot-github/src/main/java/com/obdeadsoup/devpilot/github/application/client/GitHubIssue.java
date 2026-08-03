package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

public record GitHubIssue(long githubIssueId,int number,String title,String body,String state,String stateReason,
                          Long authorGitHubUserId,String authorLogin,String assigneesJson,String labelsJson,
                          String htmlUrl,Instant createdAt,Instant updatedAt,Instant closedAt){}
