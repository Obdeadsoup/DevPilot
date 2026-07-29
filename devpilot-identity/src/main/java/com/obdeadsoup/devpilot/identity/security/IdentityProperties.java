package com.obdeadsoup.devpilot.identity.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("devpilot.identity")
public record IdentityProperties(@NotNull Duration accessTokenTtl) {

    private static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofHours(24);

    @AssertTrue(message = "access-token-ttl must be positive and no greater than 24 hours")
    public boolean isAccessTokenTtlValid() {
        return accessTokenTtl != null
                && !accessTokenTtl.isZero()
                && !accessTokenTtl.isNegative()
                && accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) <= 0;
    }
}
