package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestReviewEntity;
import java.time.LocalDateTime;

public record GitHubPullRequestReviewResponse(long id,long githubReviewId,String reviewerLogin,String state,
                                              String body,String commitSha,String htmlUrl,LocalDateTime submittedAt,
                                              LocalDateTime githubUpdatedAt,boolean externalUntrustedContent){
    public static GitHubPullRequestReviewResponse from(GitHubPullRequestReviewEntity e){return new GitHubPullRequestReviewResponse(
            e.id(),e.githubReviewId(),e.reviewerLogin(),e.state(),e.body(),e.commitSha(),e.htmlUrl(),e.submittedAt(),
            e.githubUpdatedAt(),true);}
}
