package com.obdeadsoup.devpilot.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.security.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAccessTokenServiceTest {

    private static final Duration TOKEN_TTL = Duration.ofHours(2);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-28T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisAccessTokenService accessTokenService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        accessTokenService = new RedisAccessTokenService(
                redisTemplate,
                new IdentityProperties(TOKEN_TTL),
                new SecureRandom(),
                CLOCK,
                objectMapper
        );
    }

    @Test
    void issuesAtLeast256BitRandomTokenAndStoresOnlyItsSha256KeyWithTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DevPilotUserPrincipal principal = principal();

        AccessTokenService.IssuedAccessToken first = accessTokenService.issue(principal);
        AccessTokenService.IssuedAccessToken second = accessTokenService.issue(principal);

        assertThat(Base64.getUrlDecoder().decode(first.value())).hasSize(32);
        assertThat(second.value()).isNotEqualTo(first.value());
        assertThat(first.expiresInSeconds()).isEqualTo(TOKEN_TTL.toSeconds());
        assertThat(accessTokenService.timeToLive()).isEqualTo(TOKEN_TTL);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.times(2))
                .set(keyCaptor.capture(), sessionCaptor.capture(), any(Duration.class));
        String expectedKey = "devpilot:auth:access:" + sha256(first.value());
        String firstKey = keyCaptor.getAllValues().getFirst();
        String firstSession = sessionCaptor.getAllValues().getFirst();
        assertThat(firstKey).isEqualTo(expectedKey).doesNotContain(first.value());
        assertThat(firstSession)
                .contains("\"userId\":42", "\"username\":\"alice\"", "\"issuedAt\"")
                .doesNotContain(first.value(), "alice@example.com", "passwordHash");
        verify(valueOperations).set(expectedKey, firstSession, TOKEN_TTL);
    }

    @Test
    void validStoredSessionRestoresSafePrincipal() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AccessTokenService.IssuedAccessToken issued = accessTokenService.issue(principal());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), sessionCaptor.capture(), any(Duration.class));
        when(valueOperations.get(keyCaptor.getValue())).thenReturn(sessionCaptor.getValue());

        DevPilotUserPrincipal restored = accessTokenService.resolve(issued.value()).orElseThrow();

        assertThat(restored.id()).isEqualTo(42L);
        assertThat(restored.username()).isEqualTo("alice");
        assertThat(restored.displayName()).isEqualTo("Alice");
        assertThat(restored.email()).isNull();
    }

    @Test
    void missingOrExpiredTokenReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(accessTokenService.resolve("missing-token")).isEmpty();
        assertThat(accessTokenService.resolve(" ")).isEmpty();
    }

    @Test
    void logoutDeletesHashedKeyAndIsIdempotent() throws Exception {
        String token = "logout-token";
        String expectedKey = "devpilot:auth:access:" + sha256(token);

        accessTokenService.revoke(token);
        accessTokenService.revoke(token);

        verify(redisTemplate, org.mockito.Mockito.times(2)).delete(expectedKey);
    }

    private DevPilotUserPrincipal principal() {
        return new DevPilotUserPrincipal(42L, "alice", "alice@example.com", "Alice");
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
