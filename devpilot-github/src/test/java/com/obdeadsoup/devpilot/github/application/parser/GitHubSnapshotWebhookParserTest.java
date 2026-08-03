package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSnapshotWebhookParserTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final GitHubIssueWebhookParser issues=new GitHubIssueWebhookParser(mapper);
    private final GitHubPullRequestWebhookParser pulls=new GitHubPullRequestWebhookParser(mapper);
    private final GitHubPullRequestReviewWebhookParser reviews=new GitHubPullRequestReviewWebhookParser(mapper,pulls);

    @Test void issueActionAndCurrentStatusRemainSeparate(){var parsed=issues.parse(delivery("issues",issue("reopened","open")),binding()).orElseThrow();
        assertThat(parsed.webhookAction()).isEqualTo("reopened");assertThat(parsed.status().name()).isEqualTo("OPEN");
        assertThat(parsed.githubIssueId()).isEqualTo(501);assertThat(parsed.issueNumber()).isEqualTo(12);}

    @Test void pullRequestUsesPrIdAndKeepsDraftSeparateFromStatus(){var parsed=pulls.parse(delivery("pull_request",pull("opened",true,null)),binding()).orElseThrow();
        assertThat(parsed.githubPullRequestId()).isEqualTo(701);assertThat(parsed.pullRequestNumber()).isEqualTo(22);
        assertThat(parsed.status()).isEqualTo(GitHubPullRequestStatus.OPEN);assertThat(parsed.draft()).isTrue();}

    @Test void reviewCarriesIndependentReviewIdAndPrNumber(){var parsed=reviews.parse(delivery("pull_request_review",review()),binding()).orElseThrow();
        assertThat(parsed.pullRequest().sourceEventId()).isNull();assertThat(parsed.review().githubReviewId()).isEqualTo(901);
        assertThat(parsed.review().pullRequestNumber()).isEqualTo(22);assertThat(parsed.review().status().name()).isEqualTo("APPROVED");}

    @Test void unknownActionIsSafelyRecognizedWithoutSnapshotCommand(){assertThat(issues.parse(delivery("issues",issue("milestoned","open")),binding())).isEmpty();}

    private String issue(String action,String state){return """
            {"action":"%s","repository":{"id":123},"issue":{"id":501,"number":12,"title":"Untrusted <script>",
            "body":"body","state":"%s","user":{"id":7,"login":"octo"},"assignees":[],"labels":[],
            "html_url":"https://github.com/octo/demo/issues/12","created_at":"2026-08-01T00:00:00Z",
            "updated_at":"2026-08-01T01:00:00Z"}}
            """.formatted(action,state);}
    private String pull(String action,boolean draft,String mergedAt){return """
            {"action":"%s","repository":{"id":123},"pull_request":{"id":701,"number":22,"title":"PR","body":"body",
            "state":"open","draft":%s,"merged":false,"user":{"id":8,"login":"dev"},
            "head":{"ref":"feature","sha":"%s"},"base":{"ref":"main","sha":"%s"},
            "requested_reviewers":[],"assignees":[],"labels":[],"html_url":"https://github.com/octo/demo/pull/22",
            "created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T01:00:00Z"%s}}
            """.formatted(action,draft,"a".repeat(40),"b".repeat(40),mergedAt==null?"":",\"merged_at\":\""+mergedAt+"\"");}
    private String review(){return """
            {"action":"submitted","repository":{"id":123},"pull_request":{"id":701,"number":22,"title":"PR","state":"open",
            "draft":false,"head":{"ref":"feature","sha":"%s"},"base":{"ref":"main","sha":"%s"},
            "requested_reviewers":[],"assignees":[],"labels":[],"html_url":"https://github.com/octo/demo/pull/22",
            "created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T01:00:00Z"},
            "review":{"id":901,"state":"approved","body":"ok","commit_id":"%s","user":{"id":9,"login":"reviewer"},
            "html_url":"https://github.com/octo/demo/pull/22#pullrequestreview-901","submitted_at":"2026-08-01T02:00:00Z"}}
            """.formatted("a".repeat(40),"b".repeat(40),"a".repeat(40));}
    private GitHubDeliveryEntity delivery(String type,String json){return new GitHubDeliveryEntity(1,10,20,30,"delivery-1",type,
            null,"PROCESSING",json,"hash",0,null,null,null,null,LocalDateTime.of(2026,8,1,0,0),1);}
    private GitHubRepositoryEntity binding(){return new GitHubRepositoryEntity(30,10,20,123,"octo","demo","octo/demo",
            "https://github.com/octo/demo","main","private","ACTIVE","secret","token",null,null,null,null,null,
            LocalDateTime.now(),LocalDateTime.now(),0);}
}
