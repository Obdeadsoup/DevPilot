package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContextQuery;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Python Tool 调用的业务仲裁点：从 runId 恢复权威 actor/scope，要求 RUNNING，再分派只读 allowlist。
 * Python 提供的 ToolCall 不是授权；每个 handler 都会在正式 Application Query 中重新执行 RBAC。
 */
@Service
public class AgentToolApplicationService {
    private final AgentRunExecutionContextQuery contextQuery;
    private final Map<AgentToolName, AgentReadToolHandler> handlers;
    private final AgentToolResultSizePolicy sizePolicy;

    public AgentToolApplicationService(AgentRunExecutionContextQuery contextQuery,
                                       List<AgentReadToolHandler> handlers,
                                       AgentToolResultSizePolicy sizePolicy) {
        this.contextQuery = contextQuery;
        this.sizePolicy = sizePolicy;
        EnumMap<AgentToolName, AgentReadToolHandler> indexed = new EnumMap<>(AgentToolName.class);
        for (AgentReadToolHandler handler : handlers) {
            if (indexed.putIfAbsent(handler.name(), handler) != null) {
                throw new IllegalStateException("duplicate Agent Tool handler");
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    /** 执行一次 run-bound delegation；不使用 SecurityContext，也不接受调用方声明用户与 scope。 */
    public AgentToolResult execute(AgentToolCommand command) {
        requireIdentifier(command.requestId());
        requireIdentifier(command.runId());
        requireIdentifier(command.toolCallId());
        requireIdentifier(command.toolName());
        if (command.arguments() == null) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }

        AgentRunExecutionContext context = contextQuery.findByRunIdForRuntime(command.runId())
                .orElseThrow(() -> new AgentToolException(AgentToolErrorKind.RUN_NOT_FOUND));
        if (context.status() != AgentRunStatus.RUNNING) {
            throw new AgentToolException(AgentToolErrorKind.RUN_NOT_ACTIVE);
        }
        if (!context.requestId().equals(command.requestId())) {
            throw new AgentToolException(AgentToolErrorKind.PROTOCOL);
        }
        AgentToolName name = AgentToolName.fromWireName(command.toolName())
                .orElseThrow(() -> new AgentToolException(AgentToolErrorKind.UNKNOWN_TOOL));
        if (name.risk() != AgentToolRisk.READ_ONLY) {
            // Write Tool 只能走 CreateProposal；即使模型或调用方直接请求 ExecuteTool 也不能越权。
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        AgentReadToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new AgentToolException(AgentToolErrorKind.UNKNOWN_TOOL);
        }
        Map<String, Object> data = handler.execute(context, command.arguments());
        sizePolicy.requireWithinLimit(data);
        return new AgentToolResult(
                command.requestId() + ":" + command.toolCallId(), command.toolCallId(), data);
    }

    private void requireIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
    }
}
