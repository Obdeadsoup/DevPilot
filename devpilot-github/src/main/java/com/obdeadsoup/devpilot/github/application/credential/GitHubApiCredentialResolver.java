package com.obdeadsoup.devpilot.github.application.credential;

import java.util.Optional;

public interface GitHubApiCredentialResolver {

    Optional<String> resolve(String credentialReference);
}
