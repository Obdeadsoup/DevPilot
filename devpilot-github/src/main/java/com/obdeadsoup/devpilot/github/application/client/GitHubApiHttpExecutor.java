package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.credential.GitHubAccessToken;
import com.obdeadsoup.devpilot.github.application.credential.GitHubAccessTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GitHub REST API 的唯一底层 HTTP 执行入口。
 *
 * <p>业务 Client 不应绕过本类直接调用 RestClient。本类统一负责动态 Bearer Token、Conditional
 * Header、SSRF/重定向约束、Header 提取、Error Decoder、读取 Retry、Credential 并发限制、
 * 安全结构化日志和低基数指标。</p>
 */
public final class GitHubApiHttpExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubApiHttpExecutor.class);
    private static final int MAX_SUCCESS_BODY_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GitHubAccessTokenProvider tokenProvider;
    private final GitHubCredentialConcurrencyLimiter concurrencyLimiter;
    private final GitHubApiRetryPolicy retryPolicy;
    private final GitHubApiSleeper sleeper;
    private final GitHubApiErrorDecoder errorDecoder;
    private final GitHubRateLimitParser rateLimitParser;
    private final GitHubLinkHeaderParser linkHeaderParser;
    private final GitHubApiEndpointPolicy endpointPolicy;
    private final GitHubApiMetrics metrics;

    public GitHubApiHttpExecutor(
            RestClient restClient,
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
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.concurrencyLimiter = concurrencyLimiter;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
        this.errorDecoder = errorDecoder;
        this.rateLimitParser = rateLimitParser;
        this.linkHeaderParser = linkHeaderParser;
        this.endpointPolicy = endpointPolicy;
        this.metrics = metrics;
    }

    /**
     * 对固定 Endpoint Template 执行带有限 Retry 的 GET。
     *
     * @param operation 内部低基数操作名，用于日志和指标
     * @param endpointTemplate 不含具体 Repository 的模板，只用于安全日志
     * @param pathSegments 由调用方给出的独立路径段，会被 URI Builder 编码
     * @param credentialReference 数据库存储的受限凭据引用，不是原始 Token
     * @param conditional ETag/Last-Modified；可使用 {@link GitHubConditionalRequest#none()}
     * @throws GitHubApiException 凭据、并发、网络、HTTP、限流或响应解析失败
     */
    public <T> GitHubApiResponse<T> get(
            String operation,
            String endpointTemplate,
            List<String> pathSegments,
            String credentialReference,
            GitHubConditionalRequest conditional,
            Class<T> responseType
    ) {
        URI uri = UriComponentsBuilder.fromUri(endpointPolicy.baseUrl())
                .pathSegment(pathSegments.toArray(String[]::new))
                .build()
                .encode()
                .toUri();
        return executeRead(
                HttpMethod.GET,
                operation,
                endpointTemplate,
                endpointPolicy.requireAllowed(uri),
                credentialReference,
                conditional,
                responseType
        );
    }

    /**
     * 对固定 Endpoint Template 执行 HEAD，复用与 GET 相同的凭据、限流、Retry 和安全日志链。
     */
    public GitHubApiResponse<Void> head(
            String operation,
            String endpointTemplate,
            List<String> pathSegments,
            String credentialReference,
            GitHubConditionalRequest conditional
    ) {
        URI uri = UriComponentsBuilder.fromUri(endpointPolicy.baseUrl())
                .pathSegment(pathSegments.toArray(String[]::new))
                .build()
                .encode()
                .toUri();
        return executeRead(
                HttpMethod.HEAD,
                operation,
                endpointTemplate,
                endpointPolicy.requireAllowed(uri),
                credentialReference,
                conditional,
                Void.class
        );
    }

    /** 使用已校验的 Link Cursor 读取下一页，不允许业务层自行拼接或递增页码。 */
    public <T> GitHubApiResponse<T> getPage(
            String operation,
            String endpointTemplate,
            GitHubPageCursor cursor,
            String credentialReference,
            Class<T> responseType
    ) {
        if (cursor == null || !cursor.hasNext()) {
            throw new GitHubApiException(
                    GitHubApiFailureType.VALIDATION,
                    false,
                    null,
                    null,
                    "GitHub page cursor has no next page",
                    null,
                    null
            );
        }
        return executeRead(
                HttpMethod.GET,
                operation,
                endpointTemplate,
                endpointPolicy.requireAllowed(cursor.next()),
                credentialReference,
                GitHubConditionalRequest.none(),
                responseType
        );
    }

    private <T> GitHubApiResponse<T> executeRead(
            HttpMethod method,
            String operation,
            String endpointTemplate,
            URI uri,
            String credentialReference,
            GitHubConditionalRequest conditional,
            Class<T> responseType
    ) {
        GitHubAccessToken token = tokenProvider.getToken(credentialReference)
                .orElseThrow(this::credentialUnavailable);
        try (GitHubCredentialConcurrencyLimiter.Permit ignored =
                     concurrencyLimiter.acquire(credentialReference)) {
            for (int attempt = 1; ; attempt++) {
                long startedAt = System.nanoTime();
                try {
                    GitHubApiResponse<T> response = executeWithOneSafeRedirect(
                            method, uri, token, conditional, responseType
                    );
                    long duration = System.nanoTime() - startedAt;
                    metrics.request(operation, method, response.httpStatus(), duration);
                    logSuccess(operation, endpointTemplate, attempt, duration, response);
                    return response;
                } catch (GitHubApiException failure) {
                    long duration = System.nanoTime() - startedAt;
                    metrics.request(operation, method, failure.httpStatus(), duration);
                    metrics.failure(operation, failure.failureType());
                    GitHubApiRetryPolicy.Decision decision = retryPolicy.decide(method, attempt, failure);
                    logFailure(operation, endpointTemplate, attempt, duration, failure, decision);
                    if (!decision.retry()) {
                        throw failure;
                    }
                    metrics.retry(operation, failure.failureType());
                    waitBeforeRetry(decision.delay());
                }
            }
        }
    }

    private <T> GitHubApiResponse<T> executeWithOneSafeRedirect(
            HttpMethod method,
            URI uri,
            GitHubAccessToken token,
            GitHubConditionalRequest conditional,
            Class<T> responseType
    ) {
        try {
            return executeOnce(method, uri, token, conditional, responseType);
        } catch (RedirectSignal redirect) {
            URI safeLocation = endpointPolicy.requireAllowed(redirect.location());
            try {
                return executeOnce(method, safeLocation, token, conditional, responseType);
            } catch (RedirectSignal repeatedRedirect) {
                throw new GitHubApiException(
                        GitHubApiFailureType.MALFORMED_RESPONSE,
                        false,
                        null,
                        repeatedRedirect.status(),
                        "GitHub API returned too many redirects",
                        repeatedRedirect.requestId(),
                        null
                );
            }
        }
    }

    private <T> GitHubApiResponse<T> executeOnce(
            HttpMethod method,
            URI uri,
            GitHubAccessToken token,
            GitHubConditionalRequest conditional,
            Class<T> responseType
    ) {
        try {
            RestClient.RequestHeadersSpec<?> request = request(method, uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value());
            addConditionalHeaders(request, conditional);
            return request.exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                HttpHeaders headers = response.getHeaders();
                GitHubRateLimitSnapshot rateLimit = rateLimitParser.parse(headers);
                if (isRedirect(status)) {
                    throw new RedirectSignal(
                            headers.getLocation(), status, rateLimit.requestId()
                    );
                }
                if (status == 304) {
                    // 304 是 Conditional GET 的成功结果，不交给 Error Decoder。
                    return success(status, null, true, headers, rateLimit);
                }
                if (status >= 200 && status < 300) {
                    T body = readSuccessBody(responseType, response.getBody(), rateLimit);
                    return success(status, body, false, headers, rateLimit);
                }
                throw errorDecoder.decode(
                        status,
                        headers,
                        readLimited(response.getBody(), MAX_ERROR_BODY_BYTES)
                );
            });
        } catch (GitHubApiException | RedirectSignal exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw errorDecoder.networkFailure();
        } catch (RestClientException exception) {
            throw errorDecoder.malformedResponse(null);
        }
    }

    private RestClient.RequestHeadersSpec<?> request(HttpMethod method, URI uri) {
        if (HttpMethod.GET.equals(method)) {
            return restClient.get().uri(uri);
        }
        if (HttpMethod.HEAD.equals(method)) {
            return restClient.head().uri(uri);
        }
        throw new IllegalArgumentException("Only GET and HEAD are supported by this executor");
    }

    private void addConditionalHeaders(
            RestClient.RequestHeadersSpec<?> request,
            GitHubConditionalRequest conditional
    ) {
        if (conditional == null) {
            return;
        }
        if (conditional.etag() != null && !conditional.etag().isBlank()) {
            request.header(HttpHeaders.IF_NONE_MATCH, conditional.etag());
        }
        if (conditional.lastModified() != null) {
            request.header(
                    HttpHeaders.IF_MODIFIED_SINCE,
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            conditional.lastModified().atZone(ZoneOffset.UTC)
                    )
            );
        }
    }

    private <T> T readSuccessBody(
            Class<T> responseType,
            java.io.InputStream body,
            GitHubRateLimitSnapshot rateLimit
    ) {
        if (Void.class.equals(responseType)) {
            return null;
        }
        byte[] bytes = readLimited(body, MAX_SUCCESS_BODY_BYTES);
        if (bytes.length == 0) {
            throw errorDecoder.malformedResponse(rateLimit);
        }
        try {
            return objectMapper.readValue(bytes, responseType);
        } catch (IOException exception) {
            throw errorDecoder.malformedResponse(rateLimit);
        }
    }

    private byte[] readLimited(java.io.InputStream body, int limit) {
        try {
            byte[] bytes = body.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw errorDecoder.malformedResponse(null);
            }
            return bytes;
        } catch (IOException exception) {
            throw errorDecoder.networkFailure();
        }
    }

    private <T> GitHubApiResponse<T> success(
            int status,
            T body,
            boolean notModified,
            HttpHeaders headers,
            GitHubRateLimitSnapshot rateLimit
    ) {
        long lastModifiedMillis = headers.getLastModified();
        Instant lastModified = lastModifiedMillis < 0 ? null : Instant.ofEpochMilli(lastModifiedMillis);
        return new GitHubApiResponse<>(
                status,
                body,
                notModified,
                headers.getFirst(HttpHeaders.ETAG),
                lastModified,
                rateLimit,
                linkHeaderParser.parse(headers.getFirst(HttpHeaders.LINK))
        );
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 307 || status == 308;
    }

    private void waitBeforeRetry(java.time.Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw errorDecoder.networkFailure();
        }
    }

    private GitHubApiException credentialUnavailable() {
        return new GitHubApiException(
                GitHubApiFailureType.CREDENTIAL_UNAVAILABLE,
                false,
                null,
                null,
                "GitHub API credential is unavailable",
                null,
                null
        );
    }

    private <T> void logSuccess(
            String operation,
            String endpointTemplate,
            int attempt,
            long durationNanos,
            GitHubApiResponse<T> response
    ) {
        LOGGER.info(
                "GitHub API request operation={} endpointTemplate={} status={} durationMs={} attempt={} "
                        + "requestId={} rateLimitRemaining={} rateLimitResetAt={} failureType={}",
                operation,
                endpointTemplate,
                response.httpStatus(),
                durationNanos / 1_000_000,
                attempt,
                response.rateLimit().requestId(),
                response.rateLimit().remaining(),
                response.rateLimit().resetAt(),
                "NONE"
        );
    }

    private void logFailure(
            String operation,
            String endpointTemplate,
            int attempt,
            long durationNanos,
            GitHubApiException failure,
            GitHubApiRetryPolicy.Decision decision
    ) {
        GitHubRateLimitSnapshot rateLimit = failure.rateLimit();
        LOGGER.warn(
                "GitHub API request operation={} endpointTemplate={} status={} durationMs={} attempt={} "
                        + "requestId={} rateLimitRemaining={} rateLimitResetAt={} failureType={} retryAt={} delayMs={}",
                operation,
                endpointTemplate,
                failure.httpStatus(),
                durationNanos / 1_000_000,
                attempt,
                failure.requestId(),
                rateLimit == null ? null : rateLimit.remaining(),
                rateLimit == null ? null : rateLimit.resetAt(),
                failure.failureType(),
                decision.retryAt(),
                decision.delay().toMillis()
        );
    }

    private static final class RedirectSignal extends RuntimeException {

        private final URI location;
        private final int status;
        private final String requestId;

        private RedirectSignal(URI location, int status, String requestId) {
            super("GitHub API redirect");
            this.location = location;
            this.status = status;
            this.requestId = requestId;
        }

        private URI location() {
            return location;
        }

        private int status() {
            return status;
        }

        private String requestId() {
            return requestId;
        }
    }
}
