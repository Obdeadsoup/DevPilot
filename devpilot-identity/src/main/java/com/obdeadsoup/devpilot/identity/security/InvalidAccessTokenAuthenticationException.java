package com.obdeadsoup.devpilot.identity.security;

import org.springframework.security.core.AuthenticationException;

public final class InvalidAccessTokenAuthenticationException extends AuthenticationException {

    public InvalidAccessTokenAuthenticationException() {
        super("Invalid access token");
    }
}
