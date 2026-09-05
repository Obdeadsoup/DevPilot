package com.obdeadsoup.devpilot.agent.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AgentRunErrorCode implements ErrorCode {
    AGENT_RUN_NOT_FOUND("AGENT_0401", "Agent Run 不存在", HttpStatus.NOT_FOUND),
    INVALID_AGENT_INPUT("AGENT_0402", "Agent 输入不合法", HttpStatus.BAD_REQUEST),
    AGENT_RUN_STATE_CONFLICT("AGENT_0501", "Agent Run 状态已被其他请求修改", HttpStatus.CONFLICT),
    AGENT_RUN_ID_CONFLICT("AGENT_0502", "Agent Run 标识冲突", HttpStatus.CONFLICT),
    AGENT_RUN_ALREADY_TERMINAL("AGENT_0503", "已结束的 Agent Run 不能取消", HttpStatus.CONFLICT),
    AGENT_RUN_CANCEL_FAILED("AGENT_0504", "Agent Run 取消请求未被 Runtime 接受", HttpStatus.SERVICE_UNAVAILABLE),
    AGENT_PROPOSAL_NOT_FOUND("AGENT_0505", "Agent Proposal 不存在", HttpStatus.NOT_FOUND),
    AGENT_PROPOSAL_STATE_CONFLICT("AGENT_0506", "Agent Proposal 已被其他请求处理", HttpStatus.CONFLICT),
    AGENT_PROPOSAL_ACTOR_MISMATCH("AGENT_0507", "只有原 Run 用户可以裁决 Proposal", HttpStatus.FORBIDDEN),
    AGENT_PROPOSAL_RESUME_FAILED("AGENT_0508", "Proposal 已裁决，但 Runtime 恢复失败", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_LAST_EVENT_ID("AGENT_0601", "Last-Event-ID 不合法", HttpStatus.BAD_REQUEST);

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
