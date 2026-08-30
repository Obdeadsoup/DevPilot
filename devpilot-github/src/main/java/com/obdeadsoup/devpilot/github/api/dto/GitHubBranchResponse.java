package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.application.client.GitHubBranch;

public record GitHubBranchResponse(String name, String commitSha) {
    public static GitHubBranchResponse from(GitHubBranch branch) {
        return new GitHubBranchResponse(branch.name(), branch.commitSha());
    }
}
