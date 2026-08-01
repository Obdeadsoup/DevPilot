package com.obdeadsoup.devpilot.github.application.credential;

import java.util.Optional;

/**
 * 按受限 Credential Reference 提供 GitHub Access Token。
 *
 * <p>当前实现读取环境变量 Fine-grained PAT；接口保留可空过期时间，为未来 GitHub App
 * Installation Token Provider 留出替换边界，但本阶段不实现 App JWT 或换取 Token。</p>
 */
public interface GitHubAccessTokenProvider {

    Optional<GitHubAccessToken> getToken(String credentialReference);
}
