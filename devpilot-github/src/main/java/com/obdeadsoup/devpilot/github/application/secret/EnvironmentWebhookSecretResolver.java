package com.obdeadsoup.devpilot.github.application.secret;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 通过受限 {@code credential_ref} 从环境变量解析 Webhook Secret。
 * 数据库只保存引用；解析结果只短暂用于 HMAC 验签，禁止进入日志或响应。
 */
@Component
public class EnvironmentWebhookSecretResolver implements WebhookSecretResolver {

    private static final Pattern ALLOWED_REFERENCE =
            Pattern.compile("DEVPILOT_GITHUB_WEBHOOK_SECRET_[A-Z0-9_]+");

    private final Environment environment;

    public EnvironmentWebhookSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<String> resolve(String credentialReference) {
        if (credentialReference == null || !ALLOWED_REFERENCE.matcher(credentialReference).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(environment.getProperty(credentialReference))
                .filter(value -> !value.isBlank());
    }
}
