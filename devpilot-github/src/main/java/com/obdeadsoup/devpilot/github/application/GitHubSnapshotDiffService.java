package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 当前快照的显式差异计算器；它不保存事件历史，只选择本次最主要的一项语义 Activity。 */
@Service
public class GitHubSnapshotDiffService {

    public ProjectActivityType issue(GitHubIssueEntity before, UpsertGitHubIssueCommand after) {
        if (before == null) return ProjectActivityType.GITHUB_ISSUE_CREATED;
        if (!before.state().equals(after.status().name())) {
            return "CLOSED".equals(after.status().name())
                    ? ProjectActivityType.GITHUB_ISSUE_CLOSED : ProjectActivityType.GITHUB_ISSUE_REOPENED;
        }
        if (!Objects.equals(before.assigneeSummaryJson(), after.assigneeSummaryJson())) {
            return ProjectActivityType.GITHUB_ISSUE_ASSIGNEES_CHANGED;
        }
        if (!Objects.equals(before.labelsJson(), after.labelsJson())) {
            return ProjectActivityType.GITHUB_ISSUE_LABELS_CHANGED;
        }
        if (!Objects.equals(before.title(), after.title()) || !Objects.equals(before.body(), after.body())) {
            return ProjectActivityType.GITHUB_ISSUE_EDITED;
        }
        return null;
    }

    public ProjectActivityType pullRequest(GitHubPullRequestEntity before, UpsertGitHubPullRequestCommand after) {
        if (before == null) return ProjectActivityType.GITHUB_PULL_REQUEST_CREATED;
        if (!"MERGED".equals(before.status()) && "MERGED".equals(after.status().name())) {
            return ProjectActivityType.GITHUB_PULL_REQUEST_MERGED;
        }
        if (!before.status().equals(after.status().name())) {
            return "CLOSED".equals(after.status().name())
                    ? ProjectActivityType.GITHUB_PULL_REQUEST_CLOSED
                    : ProjectActivityType.GITHUB_PULL_REQUEST_REOPENED;
        }
        if (before.draft() != after.draft()) {
            return after.draft() ? ProjectActivityType.GITHUB_PULL_REQUEST_CONVERTED_TO_DRAFT
                    : ProjectActivityType.GITHUB_PULL_REQUEST_READY_FOR_REVIEW;
        }
        if (!Objects.equals(before.headSha(), after.headSha())) {
            return ProjectActivityType.GITHUB_PULL_REQUEST_SYNCHRONIZED;
        }
        if (!Objects.equals(before.requestedReviewersJson(), after.requestedReviewersJson())) {
            return ProjectActivityType.GITHUB_PULL_REQUEST_REVIEWERS_CHANGED;
        }
        if (!Objects.equals(before.title(), after.title()) || !Objects.equals(before.body(), after.body())) {
            return ProjectActivityType.GITHUB_PULL_REQUEST_EDITED;
        }
        return null;
    }

    public ProjectActivityType review(UpsertGitHubPullRequestReviewCommand after) {
        return switch (after.status()) {
            case APPROVED -> ProjectActivityType.GITHUB_REVIEW_APPROVED;
            case CHANGES_REQUESTED -> ProjectActivityType.GITHUB_REVIEW_CHANGES_REQUESTED;
            case DISMISSED -> ProjectActivityType.GITHUB_REVIEW_DISMISSED;
            case COMMENTED -> ProjectActivityType.GITHUB_REVIEW_COMMENTED;
        };
    }
}
