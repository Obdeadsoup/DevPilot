package com.obdeadsoup.devpilot.github.application.client;

import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 按 API Credential Reference 隔离的单实例低并发限制器。
 *
 * <p>Map Key 是 Reference 的 SHA-256，而不是原始 Token；不同 Workspace/Credential 不共享全局锁。
 * 该限制只协调单个 JVM，多实例部署仍需要由更高层调度控制总体并发。</p>
 */
public final class GitHubCredentialConcurrencyLimiter {

    private final ConcurrentMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final int maxConcurrentRequests;
    private final Duration acquireTimeout;

    public GitHubCredentialConcurrencyLimiter(GitHubIntegrationProperties properties) {
        this(properties.maxConcurrentRequestsPerCredential(), properties.concurrencyAcquireTimeout());
    }

    GitHubCredentialConcurrencyLimiter(int maxConcurrentRequests, Duration acquireTimeout) {
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.acquireTimeout = acquireTimeout;
    }

    /**
     * 获取指定 Credential 的许可；调用方必须在 finally 或 try-with-resources 中关闭返回值。
     *
     * @throws GitHubApiException 等待超时或线程被中断时返回可分类的安全错误
     */
    public Permit acquire(String credentialReference) {
        String key = sha256(credentialReference);
        Semaphore semaphore = permits.computeIfAbsent(
                key, ignored -> new Semaphore(maxConcurrentRequests, true)
        );
        try {
            if (!semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw concurrencyFailure("GitHub API credential concurrency limit was reached");
            }
            return new Permit(semaphore);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw concurrencyFailure("GitHub API credential permit wait was interrupted");
        }
    }

    private String sha256(String credentialReference) {
        if (credentialReference == null || credentialReference.isBlank()) {
            throw concurrencyFailure("GitHub API credential is unavailable");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(credentialReference.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private GitHubApiException concurrencyFailure(String message) {
        return new GitHubApiException(
                GitHubApiFailureType.CONCURRENCY_LIMITED,
                false,
                null,
                null,
                message,
                null,
                null
        );
    }

    public static final class Permit implements AutoCloseable {

        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
