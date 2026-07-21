package com.obdeadsoup.devpilot.github.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "devpilot.github")
public record GitHubIntegrationProperties(
        @NotNull URI apiBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    @AssertTrue(message = "connect timeout must be positive")
    public boolean isConnectTimeoutPositive() {
        return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
    }

    @AssertTrue(message = "read timeout must be positive")
    public boolean isReadTimeoutPositive() {
        return readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative();
    }
}
