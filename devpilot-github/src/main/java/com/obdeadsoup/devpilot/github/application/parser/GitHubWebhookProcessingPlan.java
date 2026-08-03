package com.obdeadsoup.devpilot.github.application.parser;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;

import java.util.List;

public record GitHubWebhookProcessingPlan(
        RecordProjectActivityCommand aggregateActivity,
        List<UpsertGitHubCommitCommand> commits,
        List<UpsertGitHubIssueCommand> issues,
        List<UpsertGitHubPullRequestCommand> pullRequests,
        List<UpsertGitHubPullRequestReviewCommand> reviews
) {

    public GitHubWebhookProcessingPlan {
        commits = List.copyOf(commits);
        issues = List.copyOf(issues);
        pullRequests = List.copyOf(pullRequests);
        reviews = List.copyOf(reviews);
    }

    public GitHubWebhookProcessingPlan(RecordProjectActivityCommand aggregateActivity,
                                       List<UpsertGitHubCommitCommand> commits) {
        this(aggregateActivity, commits, List.of(), List.of(), List.of());
    }
}
