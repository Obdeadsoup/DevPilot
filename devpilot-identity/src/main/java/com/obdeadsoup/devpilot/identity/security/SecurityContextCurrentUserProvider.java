package com.obdeadsoup.devpilot.identity.security;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof DevPilotUserPrincipal principal)) {
            throw new BusinessException(IdentityErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.id();
    }
}
