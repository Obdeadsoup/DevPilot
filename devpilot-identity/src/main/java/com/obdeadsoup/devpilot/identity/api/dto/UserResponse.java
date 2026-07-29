package com.obdeadsoup.devpilot.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        long id,
        String username,
        String email,
        String displayName
) {

    public static UserResponse from(DevPilotUserPrincipal principal) {
        return new UserResponse(
                principal.id(),
                principal.username(),
                principal.email(),
                principal.displayName()
        );
    }
}
