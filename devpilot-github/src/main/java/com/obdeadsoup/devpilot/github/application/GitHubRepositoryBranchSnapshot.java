package com.obdeadsoup.devpilot.github.application;

/**
 * Agent Run 创建时从 ACTIVE GitHub Repository 解析出的不可变代码上下文。
 *
 * <p>它只在 Java Core 内部传递；浏览器只提供逻辑 branchName，commit SHA 必须由 GitHub
 * Client 在创建 Run 前解析，随后由 Agent 模块持久化为 Run snapshot。</p>
 */
public record GitHubRepositoryBranchSnapshot(
        String repositoryFullName,
        String branchName,
        String commitSha
) {
}
