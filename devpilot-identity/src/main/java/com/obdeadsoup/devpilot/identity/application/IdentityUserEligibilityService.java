package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向其他业务模块提供最小的本地用户有效性判断，避免跨模块直接依赖 Identity Mapper 或持久化实体。
 */
@Service
public class IdentityUserEligibilityService {

    private final UserMapper userMapper;

    public IdentityUserEligibilityService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 只有未删除且状态为 ACTIVE 的本地用户才可成为业务资源受让人。 */
    @Transactional(readOnly = true)
    public boolean isActive(long userId) {
        return userMapper.countActiveById(userId) == 1;
    }
}
