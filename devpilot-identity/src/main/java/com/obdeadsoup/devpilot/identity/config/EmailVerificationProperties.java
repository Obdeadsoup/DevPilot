package com.obdeadsoup.devpilot.identity.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 邮箱注册验证码的短生命周期、冷却和失败阈值配置，避免业务代码散落时间常量。 */
@Validated
@ConfigurationProperties("devpilot.identity.verification.email")
public record EmailVerificationProperties(
        @NotNull Duration codeTtl,
        @NotNull Duration cooldown,
        @NotNull Duration ipCooldown,
        @NotNull Duration failureTtl,
        @Min(1) @Max(20) int maxFailures
) {
}
