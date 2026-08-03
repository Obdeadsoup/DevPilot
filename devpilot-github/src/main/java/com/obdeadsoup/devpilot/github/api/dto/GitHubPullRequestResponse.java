package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import java.time.LocalDateTime;

public record GitHubPullRequestResponse(long id,long githubRepositoryId,long githubPullRequestId,int number,String title,
                                        String body,String status,boolean draft,String authorLogin,String headRef,
                                        String headSha,String baseRef,String baseSha,String mergeCommitSha,
                                        String requestedReviewersJson,String assigneesJson,String labelsJson,String htmlUrl,
                                        LocalDateTime githubCreatedAt,LocalDateTime githubUpdatedAt,
                                        LocalDateTime githubClosedAt,LocalDateTime githubMergedAt,
                                        boolean externalUntrustedContent){
    public static GitHubPullRequestResponse list(GitHubPullRequestEntity e){return from(e,false);}
    public static GitHubPullRequestResponse detail(GitHubPullRequestEntity e){return from(e,true);}
    private static GitHubPullRequestResponse from(GitHubPullRequestEntity e,boolean detail){return new GitHubPullRequestResponse(
            e.id(),e.githubRepositoryId(),e.githubPullRequestId(),e.pullRequestNumber(),e.title(),detail?e.body():null,
            e.status(),e.draft(),e.authorLogin(),e.headRef(),e.headSha(),e.baseRef(),e.baseSha(),e.mergeCommitSha(),
            e.requestedReviewersJson(),e.assigneeSummaryJson(),e.labelsJson(),e.htmlUrl(),e.githubCreatedAt(),
            e.githubUpdatedAt(),e.githubClosedAt(),e.githubMergedAt(),true);}
}
