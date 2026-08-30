package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserAccountService {

    private final UserMapper userMapper;

    public UserAccountService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public boolean isActive(long userId) {
        return userMapper.countActiveById(userId) == 1;
    }

    /** Workspace 邀请按已验证的本地账户邮箱解析，避免把邮箱地址本身当成身份。 */
    @Transactional(readOnly = true)
    public Optional<Long> findActiveUserIdByEmail(String email) {
        return userMapper.findActiveByEmail(email.strip()).map(entity -> entity.id());
    }
}
