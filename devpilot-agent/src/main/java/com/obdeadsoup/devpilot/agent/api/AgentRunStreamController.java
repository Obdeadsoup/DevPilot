package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.sse.AgentRunEventHub;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AgentRun SSE 入口；先复用 scoped AGENT_READ 查询，再注册 run-scoped emitter。 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs")
public class AgentRunStreamController {
    private final AgentRunApplicationService applicationService;
    private final AgentRunEventHub eventHub;

    public AgentRunStreamController(AgentRunApplicationService applicationService,
                                    AgentRunEventHub eventHub) {
        this.applicationService = applicationService;
        this.eventHub = eventHub;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable @Positive long workspaceId,
                             @PathVariable @Positive long projectId,
                             @PathVariable @Size(max = 64)
                             @Pattern(regexp = "[A-Za-z0-9-]+") String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false)
                             String lastEventId) {
        // get() 先解析当前用户、AGENT_READ 和 workspace/project/run scope；runId 本身不授权。
        applicationService.get(workspaceId, projectId, runId);
        return eventHub.register(runId, parseLastSequence(runId, lastEventId));
    }

    private Long parseLastSequence(String runId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        String prefix = runId + ":";
        if (!lastEventId.startsWith(prefix)) {
            throw new BusinessException(AgentRunErrorCode.INVALID_LAST_EVENT_ID);
        }
        try {
            long sequence = Long.parseLong(lastEventId.substring(prefix.length()));
            if (sequence < 1) {
                throw new NumberFormatException("non-positive");
            }
            return sequence;
        } catch (NumberFormatException exception) {
            throw new BusinessException(AgentRunErrorCode.INVALID_LAST_EVENT_ID);
        }
    }
}
