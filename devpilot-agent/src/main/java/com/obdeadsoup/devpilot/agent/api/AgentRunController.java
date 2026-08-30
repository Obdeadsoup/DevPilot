package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.api.dto.AgentRunResponse;
import com.obdeadsoup.devpilot.agent.api.dto.AgentRunHistoryResponse;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRunHistoryItem;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.agent.api.dto.StartAgentRunRequest;
import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AgentRun HTTP 入口；身份、Project RBAC、状态流转与事务均由应用服务负责。 */
@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs")
public class AgentRunController {
    private final AgentRunApplicationService applicationService;

    public AgentRunController(AgentRunApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AgentRunResponse>> start(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody StartAgentRunRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(AgentRunResponse.from(
                applicationService.start(workspaceId, projectId, request.input()))));
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunResponse> get(@PathVariable @Positive long workspaceId,
                                             @PathVariable @Positive long projectId,
                                             @PathVariable
                                             @Size(max = 64)
                                             @Pattern(regexp = "[A-Za-z0-9-]+") String runId) {
        return ApiResponse.success(AgentRunResponse.from(
                applicationService.get(workspaceId, projectId, runId)));
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentRunHistoryResponse>> list(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @RequestParam(required = false) AgentRunStatus status,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "20") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int size) {
        PageResponse<AgentRunHistoryItem> response = applicationService
                .listHistory(workspaceId, projectId, status, page, size);
        return ApiResponse.success(new PageResponse<>(response.page(), response.size(), response.total(),
                response.items().stream().map(AgentRunHistoryResponse::from).toList()));
    }


    @PostMapping("/{runId}/cancel")
    public ApiResponse<AgentRunResponse> cancel(@PathVariable @Positive long workspaceId,
                                                @PathVariable @Positive long projectId,
                                                @PathVariable
                                                @Size(max = 64)
                                                @Pattern(regexp = "[A-Za-z0-9-]+") String runId) {
        return ApiResponse.success(AgentRunResponse.from(
                applicationService.cancel(workspaceId, projectId, runId)));
    }
}
