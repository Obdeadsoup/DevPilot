package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
