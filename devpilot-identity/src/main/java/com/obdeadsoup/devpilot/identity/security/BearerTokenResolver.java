package com.obdeadsoup.devpilot.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class BearerTokenResolver {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_AUTHORIZATION_LENGTH = 1024;

    public Optional<String> resolve(HttpServletRequest request) {
        return resolve(request.getHeader(AUTHORIZATION));
    }

    public Optional<String> resolve(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        if (authorizationHeader.length() > MAX_AUTHORIZATION_LENGTH
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new InvalidAccessTokenAuthenticationException();
        }
        String accessToken = authorizationHeader.substring(BEARER_PREFIX.length());
        if (accessToken.isBlank() || accessToken.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidAccessTokenAuthenticationException();
        }
        return Optional.of(accessToken);
    }
}
