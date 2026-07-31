package com.obdeadsoup.devpilot.github.domain;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;

import java.util.regex.Pattern;

public record GitHubRepositoryReference(String owner, String repositoryName) {

    private static final Pattern OWNER = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"
    );
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    public static GitHubRepositoryReference from(String owner, String repositoryName) {
        String normalizedOwner = strip(owner);
        String normalizedRepositoryName = strip(repositoryName);
        if (normalizedOwner == null
                || !OWNER.matcher(normalizedOwner).matches()
                || normalizedOwner.contains("--")
                || normalizedRepositoryName == null
                || !REPOSITORY.matcher(normalizedRepositoryName).matches()
                || ".".equals(normalizedRepositoryName)
                || "..".equals(normalizedRepositoryName)) {
            throw new BusinessException(GitHubRepositoryErrorCode.INVALID_REPOSITORY_REFERENCE);
        }
        return new GitHubRepositoryReference(normalizedOwner, normalizedRepositoryName);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
