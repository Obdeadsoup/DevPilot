package com.obdeadsoup.devpilot.audit.api;

import com.obdeadsoup.devpilot.audit.application.DeadLetterQueryService;
import com.obdeadsoup.devpilot.audit.application.OutboxReplayApplicationService;
import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/operations/outbox")
public class OutboxOperationsController {
    private final DeadLetterQueryService queries;private final OutboxReplayApplicationService replays;
    public OutboxOperationsController(DeadLetterQueryService queries,OutboxReplayApplicationService replays){
        this.queries=queries;this.replays=replays;}
    @GetMapping("/dead")
    public ApiResponse<PageResponse<DeadOutboxEventResponse>> dead(@PathVariable long workspaceId,@PathVariable long projectId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success(queries.outbox(workspaceId,projectId,page,size));}
    @GetMapping("/{eventId}")
    public ApiResponse<DeadOutboxEventResponse> detail(@PathVariable long workspaceId,@PathVariable long projectId,
                                                       @PathVariable long eventId){
        return ApiResponse.success(queries.outboxDetail(workspaceId,projectId,eventId));}
    @PostMapping("/{eventId}/replays")
    public ResponseEntity<ApiResponse<ReplayReceiptResponse>> replay(@PathVariable long workspaceId,@PathVariable long projectId,
            @PathVariable long eventId,@Valid @RequestBody ReplayRequest request){
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(replays.replay(workspaceId,projectId,eventId,request)));}
}
