package com.obdeadsoup.devpilot.github.application.credential;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从 Spring Environment 解析 Fine-grained PAT 的当前实现。
 *
 * <p>只接受 {@code DEVPILOT_GITHUB_API_TOKEN_[A-Z0-9_]+}，防止 Binding 把任意配置项
 * 当作凭据读取；原始 Token 不会被缓存、记录或返回给业务 Controller。</p>
 */
@Component
public class EnvironmentGitHubAccessTokenProvider implements GitHubAccessTokenProvider {

    private static final Pattern ALLOWED_REFERENCE =
            Pattern.compile("DEVPILOT_GITHUB_API_TOKEN_[A-Z0-9_]+");

    private final Environment environment;

    public EnvironmentGitHubAccessTokenProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Optional<GitHubAccessToken> getToken(String credentialReference) {
        if (credentialReference == null || !ALLOWED_REFERENCE.matcher(credentialReference).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(environment.getProperty(credentialReference))
                .filter(value -> !value.isBlank())
                .map(value -> new GitHubAccessToken(value, null));
    }
}
