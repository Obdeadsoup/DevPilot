package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.api.dto.AgentRunResponse;
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
}
