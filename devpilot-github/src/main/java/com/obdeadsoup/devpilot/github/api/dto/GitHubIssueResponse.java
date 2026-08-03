package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import java.time.LocalDateTime;

public record GitHubIssueResponse(long id,long githubRepositoryId,long githubIssueId,int number,String title,
                                  String body,String state,String stateReason,String authorLogin,
                                  String assigneesJson,String labelsJson,String htmlUrl,LocalDateTime githubCreatedAt,
                                  LocalDateTime githubUpdatedAt,LocalDateTime githubClosedAt,
                                  boolean externalUntrustedContent){
    public static GitHubIssueResponse list(GitHubIssueEntity e){return from(e,false);}
    public static GitHubIssueResponse detail(GitHubIssueEntity e){return from(e,true);}
    private static GitHubIssueResponse from(GitHubIssueEntity e,boolean detail){return new GitHubIssueResponse(e.id(),
            e.githubRepositoryId(),e.githubIssueId(),e.issueNumber(),e.title(),detail?e.body():null,e.state(),
            e.stateReason(),e.authorLogin(),e.assigneeSummaryJson(),e.labelsJson(),e.htmlUrl(),e.githubCreatedAt(),
            e.githubUpdatedAt(),e.githubClosedAt(),true);}
}
