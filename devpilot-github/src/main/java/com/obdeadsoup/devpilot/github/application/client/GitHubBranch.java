package com.obdeadsoup.devpilot.github.application.client;

/** GitHub 分支的最小只读投影，commitSha 是启动 Agent 时可持久化的不可变上下文。 */
public record GitHubBranch(String name, String commitSha) { }
