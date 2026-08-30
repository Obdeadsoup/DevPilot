package com.obdeadsoup.devpilot.github.application.client;

import java.util.List;

public interface GitHubBranchClient {
    List<GitHubBranch> listBranches(String owner, String repositoryName, String apiCredentialReference);
}
