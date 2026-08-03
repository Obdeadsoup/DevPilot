package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

public record GitHubPullRequestReview(long githubReviewId,Long reviewerGitHubUserId,String reviewerLogin,
                                      String state,String body,String commitSha,String htmlUrl,
                                      Instant submittedAt,Instant updatedAt){}
