package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** PR Webhook 强类型 Parser；GitHub PR ID、Issue ID 和 Repository 内 number 明确分离。 */
@Component
public class GitHubPullRequestWebhookParser extends GitHubWebhookParserSupport {
    private static final Set<String> ACTIONS=Set.of("opened","edited","closed","reopened","synchronize",
            "ready_for_review","converted_to_draft","assigned","unassigned","review_requested","review_request_removed");
    public GitHubPullRequestWebhookParser(ObjectMapper objectMapper){super(objectMapper);}

    public Optional<UpsertGitHubPullRequestCommand> parse(GitHubDeliveryEntity d,GitHubRepositoryEntity b){
        JsonNode root=root(d,b);String action=requiredText(root,"action");if(!ACTIONS.contains(action))return Optional.empty();
        return Optional.of(parseSnapshot(d,b,root.path("pull_request"),action,d.githubDeliveryId()));
    }

    UpsertGitHubPullRequestCommand parseSnapshot(GitHubDeliveryEntity d,GitHubRepositoryEntity b,JsonNode pr,
                                                 String action,String sourceEventId){
        long id=requiredLong(pr,"id");int number=requiredInt(pr,"number");String state=requiredText(pr,"state");
        boolean merged=pr.path("merged").asBoolean(false);var mergedAt=time(pr,"merged_at");
        GitHubPullRequestStatus status=GitHubPullRequestStatus.from(state,merged,mergedAt!=null);
        JsonNode user=pr.path("user"),head=pr.path("head"),base=pr.path("base");
        Long issueId=pr.path("github_issue_id").canConvertToLong()
                ?pr.path("github_issue_id").longValue():null;
        return new UpsertGitHubPullRequestCommand(d.workspaceId(),d.projectId(),b.id(),b.githubRepositoryId(),
                b.fullName(),id,issueId,number,requiredText(pr,"title"),text(pr,"body"),status,
                pr.path("draft").asBoolean(false),nullableId(user),text(user,"login"),requiredText(head,"ref"),
                requiredText(head,"sha"),requiredText(base,"ref"),requiredText(base,"sha"),text(pr,"merge_commit_sha"),
                names(pr.path("requested_reviewers"),"login"),names(pr.path("assignees"),"login"),
                names(pr.path("labels"),"name"),requiredText(pr,"html_url"),requiredTime(pr,"created_at"),
                requiredTime(pr,"updated_at"),time(pr,"closed_at"),mergedAt,GitHubSnapshotSource.WEBHOOK,
                sourceEventId,action,null);
    }
}
