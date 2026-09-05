package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.api.dto.*;
import com.obdeadsoup.devpilot.agent.application.proposal.AgentToolProposalWorkflow;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs/{runId}/proposals")
public class AgentToolProposalController {
    private final AgentToolProposalWorkflow workflow;
    public AgentToolProposalController(AgentToolProposalWorkflow workflow) { this.workflow = workflow; }

    @GetMapping("/pending")
    public ApiResponse<AgentToolProposalResponse> pending(
            @PathVariable @Positive long workspaceId, @PathVariable @Positive long projectId,
            @PathVariable @Size(max=64) @Pattern(regexp="[A-Za-z0-9-]+") String runId) {
        return ApiResponse.success(AgentToolProposalResponse.from(
                workflow.getPending(workspaceId, projectId, runId)));
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<AgentToolProposalResponse> get(
            @PathVariable @Positive long workspaceId, @PathVariable @Positive long projectId,
            @PathVariable @Size(max=64) @Pattern(regexp="[A-Za-z0-9-]+") String runId,
            @PathVariable @Size(max=64) @Pattern(regexp="[A-Za-z0-9-]+") String proposalId) {
        return ApiResponse.success(AgentToolProposalResponse.from(
                workflow.get(workspaceId, projectId, runId, proposalId)));
    }

    @PostMapping("/{proposalId}/decision")
    public ApiResponse<AgentToolProposalResponse> decide(
            @PathVariable @Positive long workspaceId, @PathVariable @Positive long projectId,
            @PathVariable @Size(max=64) @Pattern(regexp="[A-Za-z0-9-]+") String runId,
            @PathVariable @Size(max=64) @Pattern(regexp="[A-Za-z0-9-]+") String proposalId,
            @Valid @RequestBody AgentToolProposalDecisionRequest request) {
        return ApiResponse.success(AgentToolProposalResponse.from(
                workflow.decide(workspaceId, projectId, runId, proposalId, request.decision())));
    }
}
