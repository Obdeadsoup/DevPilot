package com.obdeadsoup.devpilot.github.application.parser;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;

import java.util.List;

public record GitHubWebhookProcessingPlan(
        RecordProjectActivityCommand aggregateActivity,
        List<UpsertGitHubCommitCommand> commits
) {

    public GitHubWebhookProcessingPlan {
        commits = List.copyOf(commits);
    }
}
