package com.obdeadsoup.devpilot.agent.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AgentRunErrorCode implements ErrorCode {
    AGENT_RUN_NOT_FOUND("AGENT_0401", "Agent Run 不存在", HttpStatus.NOT_FOUND),
    INVALID_AGENT_INPUT("AGENT_0402", "Agent 输入不合法", HttpStatus.BAD_REQUEST),
    AGENT_RUN_STATE_CONFLICT("AGENT_0501", "Agent Run 状态已被其他请求修改", HttpStatus.CONFLICT),
    AGENT_RUN_ID_CONFLICT("AGENT_0502", "Agent Run 标识冲突", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus status;

    AgentRunErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String code() { return code; }
    public String message() { return message; }
    public HttpStatus status() { return status; }
}
