package com.obdeadsoup.devpilot.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.identity.application.AccessTokenService;
import com.obdeadsoup.devpilot.identity.application.RedisAccessTokenService;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.security.IdentityProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisAccessTokenIntegrationTest {

    private static final Duration TOKEN_TTL = Duration.ofHours(2);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisAccessTokenService accessTokenService;

    @BeforeAll
    static void createTokenService() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        accessTokenService = new RedisAccessTokenService(
                redisTemplate,
                new IdentityProperties(TOKEN_TTL),
                new SecureRandom(),
                Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @AfterAll
    static void destroyConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void realRedisStoresHashedKeyWithTtlAndRestoresPrincipal() throws Exception {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                42L, "alice", "alice@example.com", "Alice"
        );

        AccessTokenService.IssuedAccessToken issued = accessTokenService.issue(principal);
        String expectedKey = redisKey(issued.value());
        Set<String> storedKeys = redisTemplate.keys("devpilot:auth:access:*");
        Long ttlSeconds = redisTemplate.getExpire(expectedKey);
        String storedSession = redisTemplate.opsForValue().get(expectedKey);

        assertThat(storedKeys).containsExactly(expectedKey);
        assertThat(expectedKey).doesNotContain(issued.value());
        assertThat(ttlSeconds).isBetween(7198L, 7200L);
        assertThat(storedSession)
                .contains("\"userId\":42", "\"username\":\"alice\"")
                .doesNotContain(issued.value(), "alice@example.com", "passwordHash");
        DevPilotUserPrincipal restored = accessTokenService.resolve(issued.value()).orElseThrow();
        assertThat(restored.id()).isEqualTo(42L);
        assertThat(restored.username()).isEqualTo("alice");
        assertThat(restored.displayName()).isEqualTo("Alice");
        assertThat(restored.email()).isNull();
    }

    @Test
    void realRedisExpirationAndLogoutInvalidateTokenWithoutDelay() throws Exception {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(42L, "alice", null, "Alice");
        AccessTokenService.IssuedAccessToken expired = accessTokenService.issue(principal);
        redisTemplate.expire(redisKey(expired.value()), Duration.ZERO);

        assertThat(accessTokenService.resolve(expired.value())).isEmpty();

        AccessTokenService.IssuedAccessToken revoked = accessTokenService.issue(principal);
        accessTokenService.revoke(revoked.value());
        accessTokenService.revoke(revoked.value());
        assertThat(accessTokenService.resolve(revoked.value())).isEmpty();
    }

    private static String redisKey(String accessToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(accessToken.getBytes(StandardCharsets.UTF_8));
        return "devpilot:auth:access:" + HexFormat.of().formatHex(digest);
    }
}
