package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.command.*;
import com.obdeadsoup.devpilot.github.domain.*;
import com.obdeadsoup.devpilot.github.persistence.entity.*;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSnapshotDiffServiceTest{
    private static final LocalDateTime T=LocalDateTime.of(2026,8,1,0,0);
    private final GitHubSnapshotDiffService diff=new GitHubSnapshotDiffService();
    @Test void detectsIssueStateBeforeTextChanges(){assertThat(diff.issue(issueEntity("OPEN","a","[]","[]"),
            issueCommand(GitHubIssueStatus.CLOSED,"b","[\"dev\"]","[\"bug\"]"))).isEqualTo(ProjectActivityType.GITHUB_ISSUE_CLOSED);}
    @Test void detectsPrDraftAndHeadShaSeparately(){assertThat(diff.pullRequest(prEntity(false,"a".repeat(40)),
            prCommand(true,"b".repeat(40)))).isEqualTo(ProjectActivityType.GITHUB_PULL_REQUEST_CONVERTED_TO_DRAFT);}
    @Test void mapsReviewStates(){assertThat(diff.review(reviewCommand(GitHubPullRequestReviewStatus.CHANGES_REQUESTED)))
            .isEqualTo(ProjectActivityType.GITHUB_REVIEW_CHANGES_REQUESTED);}
    @Test void computesMergedClosedOpenAndKeepsDraftOutOfStatus(){assertThat(GitHubPullRequestStatus.from("open",false,false)).isEqualTo(GitHubPullRequestStatus.OPEN);
        assertThat(GitHubPullRequestStatus.from("closed",false,false)).isEqualTo(GitHubPullRequestStatus.CLOSED);
        assertThat(GitHubPullRequestStatus.from("closed",false,true)).isEqualTo(GitHubPullRequestStatus.MERGED);}
    private GitHubIssueEntity issueEntity(String state,String title,String assignees,String labels){return new GitHubIssueEntity(1,1,2,3,4,5,6,title,"body",state,null,
            null,"octo",assignees,labels,"https://github.com/o/r/issues/6",T,T,null,"WEBHOOK","h",T,T,0);}
    private UpsertGitHubIssueCommand issueCommand(GitHubIssueStatus state,String title,String assignees,String labels){return new UpsertGitHubIssueCommand(1,2,3,4,"o/r",5,6,title,"body",state,null,null,"octo",assignees,labels,
            "https://github.com/o/r/issues/6",T,T,null, GitHubSnapshotSource.WEBHOOK,"d","edited","h");}
    private GitHubPullRequestEntity prEntity(boolean draft,String sha){return new GitHubPullRequestEntity(1,1,2,3,4,7,null,8,"p","b","OPEN",draft,null,"octo",
            "f",sha,"main","b".repeat(40),null,"[]","[]","[]","https://github.com/o/r/pull/8",T,T,null,null,null,
            "WEBHOOK","h",T,T,0);}
    private UpsertGitHubPullRequestCommand prCommand(boolean draft,String sha){return new UpsertGitHubPullRequestCommand(1,2,3,4,"o/r",7,null,8,"p","b",
            GitHubPullRequestStatus.OPEN,draft,null,"octo","f",sha,"main","b".repeat(40),null,"[]","[]","[]",
            "https://github.com/o/r/pull/8",T,T,null,null,GitHubSnapshotSource.WEBHOOK,"d","edited","h");}
    private UpsertGitHubPullRequestReviewCommand reviewCommand(GitHubPullRequestReviewStatus status){return new UpsertGitHubPullRequestReviewCommand(
            1,2,3,4,"o/r",8,9,null,"reviewer",status,"body","a".repeat(40),"https://github.com/o/r/pull/8#review",T,T,
            GitHubSnapshotSource.WEBHOOK,"d","submitted","h");}
}
