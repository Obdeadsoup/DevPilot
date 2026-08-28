package com.obdeadsoup.devpilot.agent.application.tool;

/** 跨 gRPC 边界稳定暴露的 Tool 失败分类，不包含异常文本或实现类名。 */
public enum AgentToolErrorKind {
    UNKNOWN_TOOL,
    INVALID_ARGUMENT,
    RUN_NOT_FOUND,
    RUN_NOT_ACTIVE,
    PERMISSION_DENIED,
    NOT_FOUND,
    RESULT_TOO_LARGE,
    INTERNAL,
    PROTOCOL
}
