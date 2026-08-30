package com.obdeadsoup.devpilot.identity.infrastructure.mail;

import com.obdeadsoup.devpilot.identity.application.port.VerificationCodeSender;
import com.obdeadsoup.devpilot.identity.error.VerificationCodeSenderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP Adapter：仅将已生成的验证码投递到邮箱；QQ/163 参数均由 Spring mail 环境配置决定。 */
@Component
@Profile({"smtp", "prod"})
public class SmtpEmailVerificationCodeSender implements VerificationCodeSender {
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailVerificationCodeSender(JavaMailSender mailSender,
                                           @Value("${MAIL_FROM:${spring.mail.username:}}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String target, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(target);
            message.setSubject("DevPilot 注册验证码");
            message.setText("你的 DevPilot 注册验证码为：\n\n" + code
                    + "\n\n验证码 5 分钟内有效，请勿泄露给他人。\n如果这不是你的操作，请忽略本邮件。");
            mailSender.send(message);
        } catch (MailException exception) {
            throw new VerificationCodeSenderException(exception);
        }
    }
}
