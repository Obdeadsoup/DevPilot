package com.obdeadsoup.devpilot.audit.api;

import com.obdeadsoup.devpilot.audit.application.DeadLetterQueryService;
import com.obdeadsoup.devpilot.audit.application.GitHubSyncReplayApplicationService;
import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/sync-runs")
public class GitHubSyncOperationsController {
    private final DeadLetterQueryService queries;private final GitHubSyncReplayApplicationService replays;
    public GitHubSyncOperationsController(DeadLetterQueryService queries,GitHubSyncReplayApplicationService replays){
        this.queries=queries;this.replays=replays;}
    @GetMapping
    public ApiResponse<PageResponse<DeadGitHubSyncRunResponse>> runs(@PathVariable long workspaceId,@PathVariable long projectId,
            @PathVariable long bindingId,@RequestParam(defaultValue="DEAD") String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        if(!"DEAD".equals(status)) throw new BusinessException(AuditErrorCode.INVALID_DEAD_QUERY);
        return ApiResponse.success(queries.syncRuns(workspaceId,projectId,bindingId,page,size));}
    @PostMapping("/{runId}/replay")
    public ResponseEntity<ApiResponse<ReplayReceiptResponse>> replay(@PathVariable long workspaceId,@PathVariable long projectId,
            @PathVariable long bindingId,@PathVariable long runId,@Valid @RequestBody ReplayRequest request){
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(replays.replay(workspaceId,projectId,bindingId,runId,request)));}
}
