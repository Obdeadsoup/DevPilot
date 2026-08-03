package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.client.*;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.persistence.entity.*;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubSnapshotReconciliationServiceTest{
    private static final Instant NOW=Instant.parse("2026-08-01T12:00:00Z");
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private final GitHubSyncRunStateService runs=mock(GitHubSyncRunStateService.class);
    private final GitHubRepositoryMapper repositories=mock(GitHubRepositoryMapper.class);
    private final GitHubRepositoryMetadataClient metadata=mock(GitHubRepositoryMetadataClient.class);
    private final GitHubSyncCheckpointService checkpoints=mock(GitHubSyncCheckpointService.class);
    private final GitHubSyncFailureClassifier failures=mock(GitHubSyncFailureClassifier.class);

    @Test void issueFailureDoesNotAdvanceCheckpoint(){GitHubIssueClient client=mock(GitHubIssueClient.class);
        GitHubIssueApplicationService application=mock(GitHubIssueApplicationService.class);setup(GitHubSyncResourceType.ISSUE);
        var item=new GitHubIssue(501,12,"title","body","open",null,7L,"octo","[]","[]",
                "https://github.com/octo/demo/issues/12",NOW.minusSeconds(3600),NOW,null);
        when(client.listIssues(any(),any(),any(),anyInt(),any(),any())).thenReturn(new GitHubPage<>(List.of(item),GitHubPageCursor.empty()));
        when(application.upsertIssue(any())).thenThrow(new IllegalStateException("database unavailable"));
        var classification=new GitHubSyncFailureClassifier.Classification("SYNC_PROCESSING_ERROR","safe",true,null);
        when(failures.classify(any())).thenReturn(classification);
        new GitHubIssueReconciliationService(runs,repositories,metadata,checkpoints,failures,
                GitHubTestProperties.reconciliation(),clock,client,application).reconcile(1);
        verify(runs,never()).complete(any(),any(),any(),any());verify(runs).handleFailure(any(),eq(classification));}

    @Test void reviewReconciliationUsesOnlyBoundedRecentCandidates(){GitHubPullRequestMapper pullRequests=mock(GitHubPullRequestMapper.class);
        setup(GitHubSyncResourceType.PULL_REQUEST_REVIEW);when(pullRequests.findReviewCandidates(10,
                LocalDateTime.of(2026,7,25,12,0),25)).thenReturn(List.of());
        GitHubPullRequestReviewClient client=mock(GitHubPullRequestReviewClient.class);
        new GitHubPullRequestReviewReconciliationService(runs,repositories,metadata,checkpoints,failures,
                GitHubTestProperties.reconciliation(),clock,pullRequests,client,
                mock(GitHubPullRequestReviewApplicationService.class),mock(GitHubReviewSyncProgressService.class)).reconcile(1);
        verify(pullRequests).findReviewCandidates(10,LocalDateTime.of(2026,7,25,12,0),25);
        verifyNoInteractions(client);verify(runs).complete(any(),any(),eq(LocalDateTime.of(2026,8,1,12,0)),isNull());}

    private void setup(GitHubSyncResourceType resource){GitHubSyncRunEntity run=new GitHubSyncRunEntity(1,10,resource.name(),
            "SCHEDULED","RUNNING",1,null,LocalDateTime.of(2026,8,1,12,0),null,null,null,null,
            LocalDateTime.of(2026,8,1,0,0),LocalDateTime.of(2026,8,1,0,0),1);
        GitHubSyncCheckpointEntity checkpoint=new GitHubSyncCheckpointEntity(2,10,resource.name(),null,null,300,
                LocalDateTime.of(2026,8,1,0,0),LocalDateTime.of(2026,8,1,0,0),0);
        when(runs.claim(1)).thenReturn(Optional.of(run));when(repositories.findSyncTarget(10)).thenReturn(Optional.of(target()));
        when(checkpoints.getOrCreate(10,resource)).thenReturn(checkpoint);when(checkpoints.calculateSince(checkpoint,NOW)).thenReturn(NOW.minus(Duration.ofDays(7)));
        when(metadata.getRepository(any(),any(),any(),any())).thenReturn(metadata());}
    private GitHubSyncTarget target(){return new GitHubSyncTarget(10,100,200,123456,"octo","demo","octo/demo","TOKEN_REF",
            "ACTIVE",false,"ACTIVE",false,"ACTIVE",false);}
    private GitHubApiResponse<VerifiedGitHubRepository> metadata(){return new GitHubApiResponse<>(200,new VerifiedGitHubRepository(123456,
            "octo","demo","octo/demo","https://github.com/octo/demo","main","private"),false,null,null,
            new GitHubRateLimitSnapshot(5000L,4999L,1L,null,"core",null,"request"),GitHubPageCursor.empty());}
}
