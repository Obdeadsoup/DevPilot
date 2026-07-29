package com.obdeadsoup.devpilot.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.identity.api.dto.UserResponse;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final DatabaseUserDetailsService service = new DatabaseUserDetailsService(userMapper);

    @Test
    void supportsNormalizedUsernameAndEmailLogin() {
        UserEntity user = user("ACTIVE");
        when(userMapper.findByNormalizedLogin("alice")).thenReturn(Optional.of(user));
        when(userMapper.findByNormalizedLogin("alice@example.com")).thenReturn(Optional.of(user));

        UserDetails byUsername = service.loadUserByUsername("  ALICE ");
        UserDetails byEmail = service.loadUserByUsername("Alice@Example.COM");

        assertThat(byUsername.getUsername()).isEqualTo("alice");
        assertThat(byEmail.getUsername()).isEqualTo("alice");
        verify(userMapper).findByNormalizedLogin("alice");
        verify(userMapper).findByNormalizedLogin("alice@example.com");
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE,true,true",
            "LOCKED,false,true",
            "DISABLED,true,false"
    })
    void mapsAccountStatusToSpringSecurityFlags(
            String status,
            boolean accountNonLocked,
            boolean enabled
    ) {
        when(userMapper.findByNormalizedLogin("alice")).thenReturn(Optional.of(user(status)));

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.isAccountNonLocked()).isEqualTo(accountNonLocked);
        assertThat(details.isEnabled()).isEqualTo(enabled);
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void safePrincipalAndResponseDtoDoNotExposePasswordHash() throws Exception {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                42L, "alice", "alice@example.com", "Alice"
        );
        UserResponse response = UserResponse.from(principal);

        assertThat(Arrays.stream(DevPilotUserPrincipal.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("password", "passwordHash");
        assertThat(Arrays.stream(UserResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("password", "passwordHash");
        assertThat(new ObjectMapper().writeValueAsString(response)).doesNotContain("password");
    }

    private UserEntity user(String status) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 28, 12, 0);
        return new UserEntity(
                42L,
                "alice",
                "alice@example.com",
                "Alice",
                "{bcrypt}$2a$10$not-a-real-hash",
                status,
                now,
                now,
                0L,
                false
        );
    }
}
