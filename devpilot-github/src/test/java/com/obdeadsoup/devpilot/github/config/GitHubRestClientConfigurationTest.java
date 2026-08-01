package com.obdeadsoup.devpilot.github.config;

import com.obdeadsoup.devpilot.github.application.client.GitHubApiEndpointPolicy;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRestClientConfigurationTest {

    @Test
    void productionProfileRejectsLoopbackWhileExplicitTestProfileAllowsIt() {
        GitHubRestClientConfiguration configuration = new GitHubRestClientConfiguration();
        GitHubIntegrationProperties properties = GitHubTestProperties.withBaseUrl(
                URI.create("http://127.0.0.1:12345")
        );

        MockEnvironment productionEnvironment = new MockEnvironment();
        productionEnvironment.setActiveProfiles("prod");
        assertThatThrownBy(() -> configuration.githubApiEndpointPolicy(
                properties, productionEnvironment
        )).isInstanceOf(IllegalStateException.class);

        MockEnvironment testEnvironment = new MockEnvironment();
        testEnvironment.setActiveProfiles("test");
        assertThat(configuration.githubApiEndpointPolicy(properties, testEnvironment).baseUrl())
                .isEqualTo(properties.baseUrl());
    }

    @Test
    void configuredRestClientSendsStableDefaultHeadersWithoutGlobalAuthorization() throws IOException {
        AtomicReference<com.sun.net.httpserver.Headers> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/headers", exchange -> {
            captured.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            GitHubIntegrationProperties properties = GitHubTestProperties.withBaseUrl(baseUrl);
            GitHubApiEndpointPolicy endpointPolicy = new GitHubApiEndpointPolicy(baseUrl, true);
            RestClient client = new GitHubRestClientConfiguration().githubRestClient(
                    RestClient.builder(), properties, endpointPolicy
            );

            client.get().uri("/headers").retrieve().toBodilessEntity();

            assertThat(captured.get().get("Accept"))
                    .isEqualTo(List.of("application/vnd.github+json"));
            assertThat(captured.get().getFirst("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
            assertThat(captured.get().getFirst("User-Agent")).isEqualTo("DevPilot");
            assertThat(captured.get().containsKey("Authorization")).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
