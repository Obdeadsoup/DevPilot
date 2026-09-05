package com.obdeadsoup.devpilot.agent.application.tool;

/** 风险策略是受信任 Tool metadata；模型和外部文本都不能提升或降低它。 */
public enum AgentToolRisk {
    READ_ONLY,
    WRITE_REQUIRES_APPROVAL
}
