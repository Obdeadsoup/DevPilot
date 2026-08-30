package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
    private final RegistrationService service = new RegistrationService(userMapper, passwordEncoder, verificationCodeService);

    @Test
    void createsNormalizedActiveUserWithEncodedPassword() {
        when(passwordEncoder.encode("secure-password-42")).thenReturn("{bcrypt}hash");
        when(userMapper.findByNormalizedLogin("new.user")).thenReturn(Optional.of(user("new.user")));

        DevPilotUserPrincipal result = service.register(
                " New.User ", " New.User@example.com ", "secure-password-42", "000421"
        );

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.username()).isEqualTo("new.user");
        assertThat(result.email()).isEqualTo("new.user@example.com");
        verify(userMapper).insert("new.user", "new.user@example.com", "new.user", "{bcrypt}hash");
        verify(verificationCodeService).verifyAndConsume("new.user@example.com", "000421");
    }

    @Test
    void rejectsDuplicateUsernameBeforeEncodingPassword() {
        when(userMapper.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.register("alice", "new@example.com", "secure-password-42", "000421"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Username is already in use");

        verify(passwordEncoder, never()).encode(anyString());
        verify(userMapper, never()).insert(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsDuplicateEmailBeforeEncodingPassword() {
        when(userMapper.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register("new-user", "alice@example.com", "secure-password-42", "000421"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email is already in use");

        verify(passwordEncoder, never()).encode(anyString());
    }

    private UserEntity user(String username) {
        return new UserEntity(7L, username, username + "@example.com", username,
                "{bcrypt}hash", "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), 0L, false);
    }
}
