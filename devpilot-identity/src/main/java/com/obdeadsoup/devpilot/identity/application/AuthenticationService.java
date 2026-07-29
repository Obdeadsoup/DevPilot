package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.identity.security.DatabaseUserDetails;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final AccessTokenService accessTokenService;

    public AuthenticationService(
            AuthenticationManager authenticationManager,
            AccessTokenService accessTokenService
    ) {
        this.authenticationManager = authenticationManager;
        this.accessTokenService = accessTokenService;
    }

    public AuthenticationResult login(String login, String password) {
        String normalizedLogin = login.strip().toLowerCase(Locale.ROOT);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizedLogin, password)
            );
        } catch (AuthenticationException exception) {
            throw new BusinessException(IdentityErrorCode.INVALID_CREDENTIALS);
        }

        if (!(authentication.getPrincipal() instanceof DatabaseUserDetails userDetails)) {
            throw new BusinessException(IdentityErrorCode.INVALID_CREDENTIALS);
        }
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userDetails.id(),
                userDetails.getUsername(),
                userDetails.email(),
                userDetails.displayName()
        );
        AccessTokenService.IssuedAccessToken issuedToken = accessTokenService.issue(principal);
        return new AuthenticationResult(
                issuedToken.value(),
                issuedToken.expiresInSeconds(),
                principal
        );
    }

    public void logout(String accessToken) {
        accessTokenService.revoke(accessToken);
    }
}
