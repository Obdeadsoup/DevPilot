package com.obdeadsoup.devpilot.identity.infrastructure.mail;

import com.obdeadsoup.devpilot.identity.application.port.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** local/dev/test 的无外网 Sender；只记录目标已脱敏的发码动作，绝不输出验证码。 */
@Component
@Profile("!smtp & !prod")
public class LoggingVerificationCodeSender implements VerificationCodeSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    @Override
    public void send(String target, String code) {
        int at = target.indexOf('@');
        String masked = at <= 1 ? "***" : target.charAt(0) + "***" + target.substring(at);
        LOGGER.info("[DEV ONLY] verification code issued target={}", masked);
    }
}
