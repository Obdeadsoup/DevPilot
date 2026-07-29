package com.obdeadsoup.devpilot.identity.security;

import com.obdeadsoup.devpilot.identity.domain.UserStatus;
import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private static final int MAX_LOGIN_LENGTH = 254;

    private final UserMapper userMapper;

    public DatabaseUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        String normalizedLogin = normalize(login);
        UserEntity user = userMapper.findByNormalizedLogin(normalizedLogin)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new DatabaseUserDetails(
                user.id(),
                user.username(),
                user.email(),
                user.displayName(),
                user.passwordHash(),
                UserStatus.valueOf(user.status())
        );
    }

    private String normalize(String login) {
        if (login == null || login.isBlank() || login.length() > MAX_LOGIN_LENGTH) {
            throw new UsernameNotFoundException("User not found");
        }
        return login.strip().toLowerCase(Locale.ROOT);
    }
}
