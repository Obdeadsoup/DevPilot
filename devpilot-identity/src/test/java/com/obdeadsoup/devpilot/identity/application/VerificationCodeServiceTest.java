package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.port.VerificationCodeSender;
import com.obdeadsoup.devpilot.identity.config.EmailVerificationProperties;
import com.obdeadsoup.devpilot.identity.error.VerificationCodeSenderException;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationCodeServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final VerificationCodeSender sender = mock(VerificationCodeSender.class);
    private final SecureRandom random = mock(SecureRandom.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final EmailVerificationProperties properties = new EmailVerificationProperties(
            Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofMinutes(5), 5);
    private final VerificationCodeService service = new VerificationCodeService(redis, sender, properties, random, userMapper);

    @Test
    void issuesSixDigitCodeIncludingLeadingZeros() {
        when(random.nextInt(1_000_000)).thenReturn(421);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        service.issue(" Test@Example.com ", "127.0.0.1");

        verify(sender).send("test@example.com", "000421");
    }

    @Test
    void serializesLuaNumericArgumentsAsStrings() {
        when(random.nextInt(1_000_000)).thenReturn(421);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        service.issue("test@example.com", "127.0.0.1");

        verify(redis).execute(any(DefaultRedisScript.class), anyList(), eq("000421"),
                eq("300000"), eq("60000"), eq("60000"));
    }

    @Test
    void rejectsEmailAndIpCooldowns() {
        when(random.nextInt(1_000_000)).thenReturn(1);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertThatThrownBy(() -> service.issue("test@example.com", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessage("Verification code was sent recently");
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);
        assertThatThrownBy(() -> service.issue("other@example.com", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessage("Too many verification requests from this network");
    }

    @Test
    void mapsConsumptionResultsAndSenderFailureWithoutLeakingInfrastructure() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);
        assertThatThrownBy(() -> service.verifyAndConsume("test@example.com", "000421"))
                .isInstanceOf(BusinessException.class).hasMessage("Verification code is incorrect");
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(3L);
        assertThatThrownBy(() -> service.verifyAndConsume("test@example.com", "000421"))
                .isInstanceOf(BusinessException.class).hasMessage("Too many incorrect verification attempts");
        when(random.nextInt(1_000_000)).thenReturn(1);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        org.mockito.Mockito.doThrow(new VerificationCodeSenderException(new RuntimeException("smtp")))
                .when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.issue("test@example.com", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessage("Verification email could not be sent");
    }
}
