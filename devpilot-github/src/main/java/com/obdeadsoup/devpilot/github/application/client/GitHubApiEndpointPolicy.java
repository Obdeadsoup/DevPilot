package com.obdeadsoup.devpilot.github.application.client;

import java.net.URI;
import java.util.Objects;

/**
 * 统一约束 GitHub API 初始地址、重定向地址和 Link Header 地址。
 *
 * <p>生产实例只允许 {@code https://api.github.com}；测试实例可显式使用 loopback Mock Host。
 * 任何用户输入都不能通过本类改变 Scheme、Host、Port 或注入 userInfo。</p>
 */
public final class GitHubApiEndpointPolicy {

    public static final URI PUBLIC_GITHUB_API = URI.create("https://api.github.com");

    private final URI baseUrl;
    private final boolean loopbackAllowed;

    public GitHubApiEndpointPolicy(URI baseUrl, boolean loopbackAllowed) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.loopbackAllowed = loopbackAllowed;
        validateBaseUrl();
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /**
     * 校验分页或重定向 URI 与配置端点完全同源。
     *
     * @throws GitHubApiException 地址可能把 Bearer Token 发送到非 GitHub Host 时抛出
     */
    public URI requireAllowed(URI candidate) {
        if (candidate == null
                || !candidate.isAbsolute()
                || candidate.getUserInfo() != null
                || candidate.getFragment() != null
                || candidate.getHost() == null
                || !baseUrl.getScheme().equalsIgnoreCase(candidate.getScheme())
                || !baseUrl.getHost().equalsIgnoreCase(candidate.getHost())
                || effectivePort(baseUrl) != effectivePort(candidate)
                || candidate.getRawPath() == null
                || candidate.getRawPath().isBlank()) {
            throw unsafeEndpoint();
        }
        return candidate;
    }

    private void validateBaseUrl() {
        boolean publicGitHub = sameOrigin(PUBLIC_GITHUB_API, baseUrl);
        boolean loopback = loopbackAllowed
                && ("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))
                && isLoopbackHost(baseUrl.getHost());
        if ((!publicGitHub && !loopback)
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalStateException("GitHub API base URL is not allowed for this profile");
        }
    }

    private boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private GitHubApiException unsafeEndpoint() {
        return new GitHubApiException(
                GitHubApiFailureType.VALIDATION,
                false,
                null,
                null,
                "GitHub API endpoint is not allowed",
                null,
                null
        );
    }
}
