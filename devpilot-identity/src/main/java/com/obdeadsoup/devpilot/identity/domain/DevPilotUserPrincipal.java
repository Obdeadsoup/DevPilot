package com.obdeadsoup.devpilot.identity.domain;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

public record DevPilotUserPrincipal(
        long id,
        String username,
        String email,
        String displayName
) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public DevPilotUserPrincipal {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }

    @Override
    public String getName() {
        return username;
    }
}
