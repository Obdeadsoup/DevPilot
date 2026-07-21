package com.obdeadsoup.devpilot.github.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubIntegrationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubIntegrationConfiguration.class)
            .withPropertyValues(
                    "devpilot.github.api-base-url=https://api.github.com",
                    "devpilot.github.connect-timeout=3s",
                    "devpilot.github.read-timeout=10s"
            );

    @Test
    void bindsValidConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GitHubIntegrationProperties.class);

            GitHubIntegrationProperties properties = context.getBean(GitHubIntegrationProperties.class);
            assertThat(properties.apiBaseUrl()).isEqualTo(URI.create("https://api.github.com"));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void rejectsNonPositiveTimeout() {
        contextRunner
                .withPropertyValues("devpilot.github.connect-timeout=0ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("connect timeout must be positive");
                });
    }
}
