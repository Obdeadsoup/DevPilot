package com.obdeadsoup.devpilot.github.domain;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRepositoryReferenceTest {

    @ParameterizedTest
    @CsvSource({
            "octocat,hello-world",
            "octo-org,.github",
            "User42,repo_name",
            "' octocat ',' demo '"
    })
    void acceptsAndTrimsValidOwnerAndRepositoryName(String owner, String repositoryName) {
        GitHubRepositoryReference reference = GitHubRepositoryReference.from(owner, repositoryName);

        assertThat(reference.owner()).isEqualTo(owner.strip());
        assertThat(reference.repositoryName()).isEqualTo(repositoryName.strip());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "-owner", "owner-", "owner--name", "owner/name", "owner name"})
    void rejectsInvalidOwner(String owner) {
        assertInvalid(owner, "demo");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", ".", "..", "owner/repo", "repo name", "repo#fragment"})
    void rejectsInvalidRepositoryName(String repositoryName) {
        assertInvalid("octocat", repositoryName);
    }

    private void assertInvalid(String owner, String repositoryName) {
        assertThatThrownBy(() -> GitHubRepositoryReference.from(owner, repositoryName))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(GitHubRepositoryErrorCode.INVALID_REPOSITORY_REFERENCE));
    }
}
