package com.obdeadsoup.devpilot.identity.security;

import com.obdeadsoup.devpilot.identity.application.AccessTokenService;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);

    private final AccessTokenService accessTokenService;
    private final BearerTokenResolver bearerTokenResolver;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final WebAuthenticationDetailsSource authenticationDetailsSource =
            new WebAuthenticationDetailsSource();

    public BearerTokenAuthenticationFilter(
            AccessTokenService accessTokenService,
            BearerTokenResolver bearerTokenResolver,
            JsonAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.accessTokenService = accessTokenService;
        this.bearerTokenResolver = bearerTokenResolver;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (hasTrustedAuthentication()) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<String> resolvedToken;
        try {
            resolvedToken = bearerTokenResolver.resolve(request);
        } catch (InvalidAccessTokenAuthenticationException exception) {
            authenticationEntryPoint.commence(request, response, exception);
            return;
        }
        if (resolvedToken.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<DevPilotUserPrincipal> principal;
        try {
            principal = accessTokenService.resolve(resolvedToken.orElseThrow());
        } catch (RuntimeException exception) {
            LOGGER.error("Access token lookup failed exceptionType={}", exception.getClass().getName());
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new InvalidAccessTokenAuthenticationException()
            );
            return;
        }
        if (principal.isEmpty()) {
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new InvalidAccessTokenAuthenticationException()
            );
            return;
        }

        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal.orElseThrow(),
                null,
                List.of()
        );
        authentication.setDetails(authenticationDetailsSource.buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private boolean hasTrustedAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
