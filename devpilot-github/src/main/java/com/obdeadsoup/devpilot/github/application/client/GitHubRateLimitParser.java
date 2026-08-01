package com.obdeadsoup.devpilot.github.application.client;

import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 将 GitHub Rate Limit 与 Request ID Header 转为容错的安全快照。 */
public final class GitHubRateLimitParser {

    private final Clock clock;

    public GitHubRateLimitParser(Clock clock) {
        this.clock = clock;
    }

    public GitHubRateLimitSnapshot parse(HttpHeaders headers) {
        return new GitHubRateLimitSnapshot(
                parseLong(headers.getFirst("X-RateLimit-Limit")),
                parseLong(headers.getFirst("X-RateLimit-Remaining")),
                parseLong(headers.getFirst("X-RateLimit-Used")),
                parseEpochSeconds(headers.getFirst("X-RateLimit-Reset")),
                trimmed(headers.getFirst("X-RateLimit-Resource")),
                parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)),
                trimmed(headers.getFirst("X-GitHub-Request-Id"))
        );
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Instant parseEpochSeconds(String value) {
        Long seconds = parseLong(value);
        if (seconds == null || seconds < 0) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(seconds);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Long seconds = parseLong(value);
        if (seconds != null) {
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
            Duration delay = Duration.between(clock.instant(), retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
