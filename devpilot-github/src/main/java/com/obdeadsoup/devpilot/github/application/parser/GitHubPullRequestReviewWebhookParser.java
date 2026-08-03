package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestReviewStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Review Webhook 强类型 Parser。先输出嵌入的 PR 快照以满足本地外键，再以独立
 * github_review_id 输出 Review；只有 Review 使用 Delivery Activity 幂等键。
 */
@Component
public class GitHubPullRequestReviewWebhookParser extends GitHubWebhookParserSupport {
    private static final Set<String>ACTIONS=Set.of("submitted","edited","dismissed");
    private final GitHubPullRequestWebhookParser pullRequestParser;
    public GitHubPullRequestReviewWebhookParser(ObjectMapper mapper,GitHubPullRequestWebhookParser pullRequestParser){
        super(mapper);this.pullRequestParser=pullRequestParser;}
    public Optional<ParsedReview> parse(GitHubDeliveryEntity d,GitHubRepositoryEntity b){
        JsonNode root=root(d,b);String action=requiredText(root,"action");if(!ACTIONS.contains(action))return Optional.empty();
        JsonNode prNode=root.path("pull_request");
        UpsertGitHubPullRequestCommand pr=pullRequestParser.parseSnapshot(d,b,prNode,"review_context",null);
        JsonNode review=root.path("review"),user=review.path("user");long reviewId=requiredLong(review,"id");
        GitHubPullRequestReviewStatus status;
        try{status=GitHubPullRequestReviewStatus.from(requiredText(review,"state"));}catch(RuntimeException e){throw malformed();}
        var submitted=time(review,"submitted_at");if(submitted==null)submitted=requiredTime(review,"updated_at");
        var updated=time(review,"updated_at");if(updated==null)updated=submitted;
        UpsertGitHubPullRequestReviewCommand command=new UpsertGitHubPullRequestReviewCommand(d.workspaceId(),d.projectId(),
                b.id(),b.githubRepositoryId(),b.fullName(),requiredInt(prNode,"number"),reviewId,nullableId(user),
                text(user,"login"),status,text(review,"body"),requiredText(review,"commit_id"),requiredText(review,"html_url"),
                submitted,updated, GitHubSnapshotSource.WEBHOOK,d.githubDeliveryId(),action,null);
        return Optional.of(new ParsedReview(pr,command));
    }
    public record ParsedReview(UpsertGitHubPullRequestCommand pullRequest,
                               UpsertGitHubPullRequestReviewCommand review){}
}
