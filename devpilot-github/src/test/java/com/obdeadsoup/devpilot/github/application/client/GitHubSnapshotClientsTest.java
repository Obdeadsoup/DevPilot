package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubSnapshotClientsTest{
    private static final String CREDENTIAL="TOKEN_REF";
    private final GitHubApiHttpExecutor executor=mock(GitHubApiHttpExecutor.class);
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void issuesApiFiltersItemsMarkedAsPullRequests(){RestClientGitHubIssueClient client=new RestClientGitHubIssueClient(executor,mapper);
        var issue=issue(1L,11,null);var pull=issue(2L,12,mapper.createObjectNode());
        when(executor.get(any(),any(),any(),any(Map.class),any(),any(),any())).thenReturn(response(new RestClientGitHubIssueClient.IssueResponse[]{issue,pull}));
        GitHubPage<GitHubIssue>page=client.listIssues("octo","demo",Instant.EPOCH,100,CREDENTIAL,null);
        assertThat(page.items()).extracting(GitHubIssue::githubIssueId).containsExactly(1L);}

    @Test void pullRequestUsesPullsResponseIdAndFollowsLinkCursor(){RestClientGitHubPullRequestClient client=new RestClientGitHubPullRequestClient(executor,mapper);
        GitHubPageCursor cursor=new GitHubPageCursor(URI.create("https://api.github.com/repositories/1/pulls?page=2"),null,null,null);
        when(executor.getPage(any(),any(),eq(cursor),eq(CREDENTIAL),any())).thenReturn(response(new RestClientGitHubPullRequestClient.PullResponse[]{pull()}));
        assertThat(client.listPullRequests("x","y",50,CREDENTIAL,cursor).items()).singleElement()
                .extracting(GitHubPullRequest::githubPullRequestId,GitHubPullRequest::number).containsExactly(701L,22);}

    @Test void reviewPaginationPreservesIndependentReviewId(){RestClientGitHubPullRequestReviewClient client=new RestClientGitHubPullRequestReviewClient(executor,mapper);
        when(executor.get(any(),any(),any(),any(Map.class),any(),any(),any())).thenReturn(response(new RestClientGitHubPullRequestReviewClient.ReviewResponse[]{review()}));
        assertThat(client.listReviews("octo","demo",22,100,CREDENTIAL,null).items()).singleElement()
                .extracting(GitHubPullRequestReview::githubReviewId).isEqualTo(901L);}

    @Test void malformedResponseAndExecutorFailuresRemainClassified(){RestClientGitHubIssueClient client=new RestClientGitHubIssueClient(executor,mapper);
        when(executor.get(any(),any(),any(),any(Map.class),any(),any(),any())).thenReturn(response(new RestClientGitHubIssueClient.IssueResponse[]{issue(null,11,null)}));
        assertThatThrownBy(()->client.listIssues("o","r",Instant.EPOCH,100,CREDENTIAL,null))
                .isInstanceOfSatisfying(GitHubApiException.class,e->assertThat(e.failureType()).isEqualTo(GitHubApiFailureType.MALFORMED_RESPONSE));
        for(GitHubApiFailureType type:List.of(GitHubApiFailureType.RATE_LIMITED,GitHubApiFailureType.TRANSIENT_SERVER_ERROR,GitHubApiFailureType.NETWORK_ERROR)){
            GitHubApiHttpExecutor failing=mock(GitHubApiHttpExecutor.class);when(failing.get(any(),any(),any(),any(Map.class),any(),any(),any()))
                    .thenThrow(new GitHubApiException(type,true,null,null,"safe",null,null));
            assertThatThrownBy(()->new RestClientGitHubIssueClient(failing,mapper).listIssues("o","r",Instant.EPOCH,100,CREDENTIAL,null))
                    .isInstanceOfSatisfying(GitHubApiException.class,e->assertThat(e.failureType()).isEqualTo(type));}}

    private RestClientGitHubIssueClient.IssueResponse issue(Long id,int number,com.fasterxml.jackson.databind.JsonNode marker){return new RestClientGitHubIssueClient.IssueResponse(
            id,number,"title","body","open",null,new RestClientGitHubIssueClient.User(7L,"octo"),List.of(),List.of(),
            "https://github.com/octo/demo/issues/"+number,"2026-08-01T00:00:00Z","2026-08-01T01:00:00Z",null,marker);}
    private RestClientGitHubPullRequestClient.PullResponse pull(){return new RestClientGitHubPullRequestClient.PullResponse(701L,22,"PR","body","open",false,
            new RestClientGitHubPullRequestClient.User(7L,"octo"),new RestClientGitHubPullRequestClient.Ref("feature","a".repeat(40)),
            new RestClientGitHubPullRequestClient.Ref("main","b".repeat(40)),null,List.of(),List.of(),List.of(),
            "https://github.com/octo/demo/pull/22","2026-08-01T00:00:00Z","2026-08-01T01:00:00Z",null,null);}
    private RestClientGitHubPullRequestReviewClient.ReviewResponse review(){return new RestClientGitHubPullRequestReviewClient.ReviewResponse(901L,
            new RestClientGitHubPullRequestReviewClient.User(9L,"reviewer"),"APPROVED","ok","a".repeat(40),
            "https://github.com/octo/demo/pull/22#review","2026-08-01T02:00:00Z",null);}
    private <T>GitHubApiResponse<T>response(T body){return new GitHubApiResponse<>(200,body,false,null,null,
            new GitHubRateLimitSnapshot(5000L,4999L,1L,null,"core",null,"request"),GitHubPageCursor.empty());}
}
