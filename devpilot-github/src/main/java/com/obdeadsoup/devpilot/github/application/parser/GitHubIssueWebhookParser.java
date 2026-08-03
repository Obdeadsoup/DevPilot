package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubIssueStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Issues Webhook 强类型 Parser，只输出统一 Upsert 所需快照；action 与 current state 分开，
 * 正文、标题、Label 仍被标记为外部不可信文本，不会输出到日志。
 */
@Component
public class GitHubIssueWebhookParser extends GitHubWebhookParserSupport {
    private static final Set<String> ACTIONS=Set.of("opened","edited","closed","reopened","assigned","unassigned","labeled","unlabeled");
    public GitHubIssueWebhookParser(ObjectMapper objectMapper){super(objectMapper);}

    /** 未支持 action 返回 empty，由入口计低基数指标并安全忽略，不令 Delivery 失败。 */
    public Optional<UpsertGitHubIssueCommand> parse(GitHubDeliveryEntity delivery,GitHubRepositoryEntity binding){
        JsonNode root=root(delivery,binding);String action=requiredText(root,"action");if(!ACTIONS.contains(action))return Optional.empty();
        JsonNode issue=root.path("issue");long id=requiredLong(issue,"id");int number=requiredInt(issue,"number");
        String state=requiredText(issue,"state");GitHubIssueStatus status;
        try{status=GitHubIssueStatus.valueOf(state.toUpperCase(java.util.Locale.ROOT));}catch(IllegalArgumentException e){throw malformed();}
        JsonNode user=issue.path("user");
        return Optional.of(new UpsertGitHubIssueCommand(delivery.workspaceId(),delivery.projectId(),binding.id(),
                binding.githubRepositoryId(),binding.fullName(),id,number,requiredText(issue,"title"),text(issue,"body"),status,
                text(issue,"state_reason"),nullableId(user),text(user,"login"),names(issue.path("assignees"),"login"),
                names(issue.path("labels"),"name"),requiredText(issue,"html_url"),requiredTime(issue,"created_at"),
                requiredTime(issue,"updated_at"),time(issue,"closed_at"),GitHubSnapshotSource.WEBHOOK,
                delivery.githubDeliveryId(),action,null));
    }
}
