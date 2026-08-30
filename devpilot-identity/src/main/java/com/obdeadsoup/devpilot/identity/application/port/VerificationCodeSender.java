package com.obdeadsoup.devpilot.identity.application.port;

/** 向注册目标发送验证码的外部通道边界；验证码生成与 Redis 状态不属于 Adapter。 */
public interface VerificationCodeSender {
    void send(String target, String code);
}
