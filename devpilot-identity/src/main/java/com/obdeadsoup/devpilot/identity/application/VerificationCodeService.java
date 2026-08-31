package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.port.VerificationCodeSender;
import com.obdeadsoup.devpilot.identity.config.EmailVerificationProperties;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.identity.error.VerificationCodeSenderException;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * 邮箱验证码的唯一业务入口。Redis Lua 将发送预留和校验消费保持原子，
 * 防止高并发请求绕过冷却或让同一个验证码被重复用于注册。
 */
@Service
public class VerificationCodeService {
    private static final String PREFIX = "devpilot:auth:verification:email:";
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            if redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
            redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
            redis.call('PSETEX', KEYS[2], ARGV[3], '1')
            redis.call('PSETEX', KEYS[3], ARGV[4], '1')
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local issued = redis.call('GET', KEYS[1])
            if not issued then return 0 end
            if issued ~= ARGV[1] then
              local failures = redis.call('INCR', KEYS[3])
              if failures == 1 then redis.call('PEXPIRE', KEYS[3], ARGV[2]) end
              if failures >= tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); redis.call('DEL', KEYS[3]); return 3
              end
              return 2
            end
            redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); redis.call('DEL', KEYS[3]); return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CLEANUP_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeSender sender;
    private final EmailVerificationProperties properties;
    private final SecureRandom secureRandom;
    private final UserMapper userMapper;

    public VerificationCodeService(StringRedisTemplate redisTemplate, VerificationCodeSender sender,
                                   EmailVerificationProperties properties, SecureRandom secureRandom,
                                   UserMapper userMapper) {
        this.redisTemplate = redisTemplate;
        this.sender = sender;
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.userMapper = userMapper;
    }

    public void issue(String email, String sourceIp) {
        String normalizedEmail = normalize(email);
        if (userMapper.existsByEmail(normalizedEmail)) {
            throw new BusinessException(IdentityErrorCode.EMAIL_ALREADY_EXISTS);
        }
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        Long reserved = redisTemplate.execute(RESERVE_SCRIPT, keys(normalizedEmail, sourceIp), code,
                Long.toString(properties.codeTtl().toMillis()),
                Long.toString(properties.cooldown().toMillis()),
                Long.toString(properties.ipCooldown().toMillis()));
        if (reserved != null && reserved == 1L) throw new BusinessException(IdentityErrorCode.VERIFICATION_COOLDOWN);
        if (reserved != null && reserved == 2L) throw new BusinessException(IdentityErrorCode.VERIFICATION_IP_LIMITED);
        try {
            sender.send(normalizedEmail, code);
        } catch (VerificationCodeSenderException exception) {
            // 仅当仍是本请求生成的 code 才清理，避免失败补偿误删随后成功请求。
            redisTemplate.execute(CLEANUP_SCRIPT, List.of(codeKey(normalizedEmail), cooldownKey(normalizedEmail)), code);
            throw new BusinessException(IdentityErrorCode.VERIFICATION_DELIVERY_FAILED);
        }
    }

    /** 原子校验并消费；成功后任何并发请求都会看到 code 已不存在。 */
    public void verifyAndConsume(String email, String code) {
        String normalizedEmail = normalize(email);
        Long result = redisTemplate.execute(CONSUME_SCRIPT,
                List.of(codeKey(normalizedEmail), cooldownKey(normalizedEmail), failureKey(normalizedEmail)),
                code, Long.toString(properties.failureTtl().toMillis()),
                Integer.toString(properties.maxFailures()));
        if (result != null && result == 1L) return;
        if (result != null && result == 2L) throw new BusinessException(IdentityErrorCode.VERIFICATION_CODE_INVALID);
        if (result != null && result == 3L) throw new BusinessException(IdentityErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        throw new BusinessException(IdentityErrorCode.VERIFICATION_CODE_EXPIRED);
    }

    private List<String> keys(String email, String sourceIp) {
        return List.of(codeKey(email), cooldownKey(email), ipCooldownKey(sourceIp));
    }
    private String codeKey(String email) { return PREFIX + "code:" + email; }
    private String cooldownKey(String email) { return PREFIX + "cooldown:" + email; }
    private String failureKey(String email) { return PREFIX + "failures:" + email; }
    private String ipCooldownKey(String sourceIp) { return PREFIX + "ip:" + Integer.toUnsignedString(sourceIp.hashCode()); }
    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
}
