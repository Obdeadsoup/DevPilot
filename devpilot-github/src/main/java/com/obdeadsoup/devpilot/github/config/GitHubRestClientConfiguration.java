package com.obdeadsoup.devpilot.github.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiEndpointPolicy;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiErrorDecoder;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiHttpExecutor;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiMetrics;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiRetryPolicy;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiSleeper;
import com.obdeadsoup.devpilot.github.application.client.GitHubCredentialConcurrencyLimiter;
import com.obdeadsoup.devpilot.github.application.client.GitHubLinkHeaderParser;
import com.obdeadsoup.devpilot.github.application.client.GitHubRateLimitParser;
import com.obdeadsoup.devpilot.github.application.credential.GitHubAccessTokenProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GitHub RestClient 及其统一执行链的关键装配。
 *
 * <p>生产 Profile 强制使用 {@code https://api.github.com}；只有测试 Profile 可以把 Base URL
 * 指向 loopback Mock Server。Authorization 不设为默认 Header，而由 Executor 每次动态加入。</p>
 */
@Configuration(proxyBeanMethods = false)
public class GitHubRestClientConfiguration {

    private static final Set<String> LOOPBACK_TEST_PROFILES = Set.of(
            "test", "integration-test", "identity-integration-test"
    );

    @Bean
    GitHubApiEndpointPolicy githubApiEndpointPolicy(
            GitHubIntegrationProperties properties,
            Environment environment
    ) {
        // 只对白名单测试 Profile 开放 loopback，避免名称碰巧含 test 的生产 Profile 放宽 SSRF 边界。
        boolean testProfile = Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(LOOPBACK_TEST_PROFILES::contains);
        return new GitHubApiEndpointPolicy(properties.baseUrl(), testProfile);
    }

    @Bean("githubRestClient")
    RestClient githubRestClient(
            RestClient.Builder builder,
            GitHubIntegrationProperties properties,
            GitHubApiEndpointPolicy endpointPolicy
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(endpointPolicy.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", properties.apiVersion())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    @Bean
    GitHubRateLimitParser githubRateLimitParser(Clock clock) {
        return new GitHubRateLimitParser(clock);
    }

    @Bean
    GitHubApiErrorDecoder githubApiErrorDecoder(
            ObjectMapper objectMapper,
            GitHubRateLimitParser rateLimitParser,
            Clock clock
    ) {
        return new GitHubApiErrorDecoder(objectMapper, rateLimitParser, clock);
    }

    @Bean
    GitHubApiRetryPolicy githubApiRetryPolicy(
            GitHubIntegrationProperties properties,
            Clock clock
    ) {
        return new GitHubApiRetryPolicy(
                properties,
                clock,
                () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    @Bean
    GitHubCredentialConcurrencyLimiter githubCredentialConcurrencyLimiter(
            GitHubIntegrationProperties properties
    ) {
        return new GitHubCredentialConcurrencyLimiter(properties);
    }

    @Bean
    GitHubLinkHeaderParser githubLinkHeaderParser(GitHubApiEndpointPolicy endpointPolicy) {
        return new GitHubLinkHeaderParser(endpointPolicy);
    }

    @Bean
    GitHubApiSleeper githubApiSleeper() {
        return duration -> Thread.sleep(duration);
    }

    @Bean
    GitHubApiMetrics githubApiMetrics(MeterRegistry meterRegistry) {
        return new GitHubApiMetrics(meterRegistry);
    }

    @Bean
    GitHubApiHttpExecutor githubApiHttpExecutor(
            @Qualifier("githubRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            GitHubAccessTokenProvider tokenProvider,
            GitHubCredentialConcurrencyLimiter concurrencyLimiter,
            GitHubApiRetryPolicy retryPolicy,
            GitHubApiSleeper sleeper,
            GitHubApiErrorDecoder errorDecoder,
            GitHubRateLimitParser rateLimitParser,
            GitHubLinkHeaderParser linkHeaderParser,
            GitHubApiEndpointPolicy endpointPolicy,
            GitHubApiMetrics metrics
    ) {
        return new GitHubApiHttpExecutor(
                restClient,
                objectMapper,
                tokenProvider,
                concurrencyLimiter,
                retryPolicy,
                sleeper,
                errorDecoder,
                rateLimitParser,
                linkHeaderParser,
                endpointPolicy,
                metrics
        );
    }
}
