package com.obdeadsoup.devpilot.identity.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropertiesTest {

    @Test
    void acceptsPositiveTtlUpToTwentyFourHours() {
        assertThat(new IdentityProperties(Duration.ofSeconds(1)).isAccessTokenTtlValid()).isTrue();
        assertThat(new IdentityProperties(Duration.ofHours(24)).isAccessTokenTtlValid()).isTrue();
    }

    @Test
    void rejectsNonPositiveOrExcessiveTtl() {
        assertThat(new IdentityProperties(Duration.ZERO).isAccessTokenTtlValid()).isFalse();
        assertThat(new IdentityProperties(Duration.ofSeconds(-1)).isAccessTokenTtlValid()).isFalse();
        assertThat(new IdentityProperties(Duration.ofHours(24).plusSeconds(1)).isAccessTokenTtlValid()).isFalse();
    }
}
