package com.obdeadsoup.devpilot.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.security.IdentityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class RedisAccessTokenService implements AccessTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_ACCEPTED_TOKEN_LENGTH = 512;
    private static final String KEY_PREFIX = "devpilot:auth:access:";

    private final StringRedisTemplate redisTemplate;
    private final Duration timeToLive;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final ObjectWriter sessionWriter;
    private final ObjectReader sessionReader;

    public RedisAccessTokenService(
            StringRedisTemplate redisTemplate,
            IdentityProperties properties,
            SecureRandom secureRandom,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.timeToLive = properties.accessTokenTtl();
        this.secureRandom = secureRandom;
        this.clock = clock;
        ObjectMapper sessionMapper = objectMapper.copy().deactivateDefaultTyping();
        this.sessionWriter = sessionMapper.writerFor(TokenSession.class);
        this.sessionReader = sessionMapper.readerFor(TokenSession.class);
    }

    @Override
    public IssuedAccessToken issue(DevPilotUserPrincipal principal) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        TokenSession session = new TokenSession(
                principal.id(),
                principal.username(),
                principal.displayName(),
                Instant.now(clock)
        );
        redisTemplate.opsForValue().set(redisKey(accessToken), serialize(session), timeToLive);
        return new IssuedAccessToken(accessToken, timeToLive.toSeconds());
    }

    @Override
    public Optional<DevPilotUserPrincipal> resolve(String accessToken) {
        if (!hasValidInputLength(accessToken)) {
            return Optional.empty();
        }
        String key = redisKey(accessToken);
        String serializedSession = redisTemplate.opsForValue().get(key);
        if (serializedSession == null) {
            return Optional.empty();
        }
        try {
            TokenSession session = sessionReader.readValue(serializedSession);
            if (!session.isValid()) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(new DevPilotUserPrincipal(
                    session.userId(),
                    session.username(),
                    null,
                    session.displayName()
            ));
        } catch (JsonProcessingException exception) {
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    @Override
    public void revoke(String accessToken) {
        if (hasValidInputLength(accessToken)) {
            redisTemplate.delete(redisKey(accessToken));
        }
    }

    @Override
    public Duration timeToLive() {
        return timeToLive;
    }

    private String serialize(TokenSession session) {
        try {
            return sessionWriter.writeValueAsString(session);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize access token session", exception);
        }
    }

    private boolean hasValidInputLength(String accessToken) {
        return accessToken != null
                && !accessToken.isBlank()
                && accessToken.length() <= MAX_ACCEPTED_TOKEN_LENGTH;
    }

    private String redisKey(String accessToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(accessToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record TokenSession(
            long userId,
            String username,
            String displayName,
            Instant issuedAt
    ) {
        private boolean isValid() {
            return userId > 0
                    && username != null
                    && !username.isBlank()
                    && displayName != null
                    && !displayName.isBlank()
                    && issuedAt != null;
        }
    }
}
