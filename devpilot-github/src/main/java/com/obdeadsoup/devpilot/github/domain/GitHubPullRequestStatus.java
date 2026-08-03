package com.obdeadsoup.devpilot.github.domain;

public enum GitHubPullRequestStatus {
    OPEN,
    CLOSED,
    MERGED;

    public static GitHubPullRequestStatus from(String state, boolean merged, boolean hasMergedAt) {
        if (merged || hasMergedAt) {
            return MERGED;
        }
        return "closed".equalsIgnoreCase(state) ? CLOSED : OPEN;
    }
}
