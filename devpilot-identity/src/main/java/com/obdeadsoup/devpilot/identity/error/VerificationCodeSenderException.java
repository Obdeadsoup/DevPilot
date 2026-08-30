package com.obdeadsoup.devpilot.identity.error;

/** 外部验证码投递失败的稳定边界异常，不泄露 SMTP 账号、主机或底层响应。 */
public class VerificationCodeSenderException extends RuntimeException {
    public VerificationCodeSenderException(Throwable cause) {
        super("Verification code delivery failed", cause);
    }
}
