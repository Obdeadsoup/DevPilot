package com.obdeadsoup.devpilot.agent.application;

/** Run 创建时冻结的 GitHub 代码上下文；旧 Run 和无仓库上下文的 Run 保持 null。 */
public record AgentRunCodeSnapshot(
        String repositoryFullName,
        String branchName,
        String commitSha
) {
    public static AgentRunCodeSnapshot none() {
        return new AgentRunCodeSnapshot(null, null, null);
    }
}
