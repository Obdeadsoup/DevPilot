package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;

public record AuthenticationResult(
        String accessToken,
        long expiresInSeconds,
        DevPilotUserPrincipal principal
) {
}
