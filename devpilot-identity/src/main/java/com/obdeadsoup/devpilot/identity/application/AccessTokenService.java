package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;

import java.time.Duration;
import java.util.Optional;

public interface AccessTokenService {

    IssuedAccessToken issue(DevPilotUserPrincipal principal);

    Optional<DevPilotUserPrincipal> resolve(String accessToken);

    void revoke(String accessToken);

    Duration timeToLive();

    record IssuedAccessToken(String value, long expiresInSeconds) {
    }
}
