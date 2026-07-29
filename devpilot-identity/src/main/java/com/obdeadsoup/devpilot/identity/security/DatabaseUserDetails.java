package com.obdeadsoup.devpilot.identity.security;

import com.obdeadsoup.devpilot.identity.domain.UserStatus;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class DatabaseUserDetails implements UserDetails, CredentialsContainer {

    private final long id;
    private final String username;
    private final String email;
    private final String displayName;
    private final UserStatus status;
    private String password;

    public DatabaseUserDetails(
            long id,
            String username,
            String email,
            String displayName,
            String password,
            UserStatus status
    ) {
        this.id = id;
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.password = Objects.requireNonNull(password, "password must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public long id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public UserStatus status() {
        return status;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.LOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status != UserStatus.DISABLED;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}
