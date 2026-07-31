package com.obdeadsoup.devpilot.github.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.api.dto.BindGitHubRepositoryRequest;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubRepositoryApiContractTest {

    @Test
    void bindRequestAcceptsOnlyRepositoryReferenceAndCredentialReferences() {
        assertThat(componentNames(BindGitHubRepositoryRequest.class)).containsExactly(
                "owner", "repositoryName", "apiCredentialRef", "webhookSecretRef"
        ).doesNotContain(
                "githubRepositoryId", "fullName", "htmlUrl", "defaultBranch", "visibility"
        );
    }

    @Test
    void responseDoesNotExposeCredentialReferencesOrValues() throws Exception {
        GitHubRepositoryEntity entity = new GitHubRepositoryEntity(
                1, 10, 20, 30, "octo", "demo", "octo/demo",
                "https://github.com/octo/demo", "main", "private", "ACTIVE",
                "DEVPILOT_GITHUB_WEBHOOK_SECRET_PRIVATE",
                "DEVPILOT_GITHUB_API_TOKEN_PRIVATE",
                null,
                LocalDateTime.of(2026, 7, 31, 10, 0),
                42L,
                LocalDateTime.of(2026, 7, 31, 9, 0),
                LocalDateTime.of(2026, 7, 31, 10, 0),
                0
        );

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(GitHubRepositoryResponse.from(entity));

        assertThat(componentNames(GitHubRepositoryResponse.class))
                .doesNotContain("apiCredentialRef", "webhookSecretRef");
        assertThat(json)
                .contains("\"hasApiCredential\":true", "\"hasWebhookSecret\":true")
                .doesNotContain(
                        "DEVPILOT_GITHUB_API_TOKEN_PRIVATE",
                        "DEVPILOT_GITHUB_WEBHOOK_SECRET_PRIVATE"
                );
    }

    @Test
    void controllerDoesNotAcceptCurrentUserId() {
        Arrays.stream(GitHubRepositoryController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .forEach(parameter -> assertThat(parameter.getName()).isNotEqualTo("currentUserId"));
    }

    private String[] componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
