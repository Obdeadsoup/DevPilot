package com.obdeadsoup.devpilot.identity.security;

import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    public JsonAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        IdentityErrorCode errorCode = authenticationException instanceof InvalidAccessTokenAuthenticationException
                ? IdentityErrorCode.INVALID_ACCESS_TOKEN
                : IdentityErrorCode.AUTHENTICATION_REQUIRED;
        responseWriter.write(response, errorCode);
    }
}
