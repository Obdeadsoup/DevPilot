package com.obdeadsoup.devpilot.github.application.credential;

import com.obdeadsoup.devpilot.github.application.secret.EnvironmentWebhookSecretResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialReferenceResolverTest {

    @Test
    void apiCredentialResolverOnlyAcceptsDedicatedApiTokenReferences() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DEVPILOT_GITHUB_API_TOKEN_TEST", "api-token")
                .withProperty("DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST", "webhook-secret")
                .withProperty("arbitrary.property", "private-value");
        EnvironmentGitHubAccessTokenProvider resolver =
                new EnvironmentGitHubAccessTokenProvider(environment);

        assertThat(resolver.getToken("DEVPILOT_GITHUB_API_TOKEN_TEST"))
                .get()
                .extracting(GitHubAccessToken::value)
                .isEqualTo("api-token");
        assertThat(resolver.getToken("DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST")).isEmpty();
        assertThat(resolver.getToken("arbitrary.property")).isEmpty();
        assertThat(resolver.getToken("DEVPILOT_GITHUB_API_TOKEN_lowercase")).isEmpty();
        assertThat(resolver.getToken(null)).isEmpty();
        assertThat(new GitHubAccessToken("private-token", null).toString())
                .doesNotContain("private-token");
    }

    @Test
    void webhookResolverOnlyAcceptsDedicatedWebhookSecretReferences() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST", "webhook-secret")
                .withProperty("DEVPILOT_GITHUB_API_TOKEN_TEST", "api-token")
                .withProperty("arbitrary.property", "private-value");
        EnvironmentWebhookSecretResolver resolver = new EnvironmentWebhookSecretResolver(environment);

        assertThat(resolver.resolve("DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST"))
                .contains("webhook-secret");
        assertThat(resolver.resolve("DEVPILOT_GITHUB_API_TOKEN_TEST")).isEmpty();
        assertThat(resolver.resolve("arbitrary.property")).isEmpty();
        assertThat(resolver.resolve("DEVPILOT_GITHUB_WEBHOOK_SECRET_lowercase")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
