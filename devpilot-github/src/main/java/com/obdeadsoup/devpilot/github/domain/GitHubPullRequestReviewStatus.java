package com.obdeadsoup.devpilot.github.domain;

import java.util.Locale;

public enum GitHubPullRequestReviewStatus {
    COMMENTED,
    APPROVED,
    CHANGES_REQUESTED,
    DISMISSED;

    public static GitHubPullRequestReviewStatus from(String state) {
        return valueOf(state.trim().toUpperCase(Locale.ROOT));
    }
}
