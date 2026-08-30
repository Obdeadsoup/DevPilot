package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 本地账号注册的应用服务：复用 dp_user 的唯一约束和既有 PasswordEncoder，
 * 只创建 ACTIVE 用户，不签发访问令牌或绕过后续登录认证流程。
 */
@Service
public class RegistrationService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verificationCodeService;

    public RegistrationService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                               VerificationCodeService verificationCodeService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.verificationCodeService = verificationCodeService;
    }

    /**
     * 创建可登录的本地用户。用户名和邮箱先规范化为小写以符合既有数据库约束；
     * 数据库唯一索引仍是并发条件下的最终防线。
     *
     * @throws BusinessException 用户名或邮箱已被占用时抛出稳定的冲突错误
     */
    @Transactional
    public DevPilotUserPrincipal register(String username, String email, String password, String verificationCode) {
        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email);
        rejectIfAlreadyUsed(normalizedUsername, normalizedEmail);
        verificationCodeService.verifyAndConsume(normalizedEmail, verificationCode);
        try {
            userMapper.insert(normalizedUsername, normalizedEmail, normalizedUsername, passwordEncoder.encode(password));
        } catch (DuplicateKeyException exception) {
            // 先查是为了给常规冲突准确反馈；这里处理的是并发注册穿透预检查的情形。
            rejectIfAlreadyUsed(normalizedUsername, normalizedEmail);
            throw new BusinessException(IdentityErrorCode.USERNAME_ALREADY_EXISTS);
        }
        UserEntity created = userMapper.findByNormalizedLogin(normalizedUsername)
                .orElseThrow(() -> new IllegalStateException("Registered user cannot be loaded"));
        return new DevPilotUserPrincipal(created.id(), created.username(), created.email(), created.displayName());
    }

    private void rejectIfAlreadyUsed(String username, String email) {
        if (userMapper.existsByUsername(username)) {
            throw new BusinessException(IdentityErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (userMapper.existsByEmail(email)) {
            throw new BusinessException(IdentityErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
