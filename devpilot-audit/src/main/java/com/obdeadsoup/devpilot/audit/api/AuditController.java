package com.obdeadsoup.devpilot.audit.api;

import com.obdeadsoup.devpilot.audit.application.AuditQueryService;
import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/audit-logs")
public class AuditController {
    private final AuditQueryService service;
    public AuditController(AuditQueryService service){this.service=service;}
    @GetMapping
    public ApiResponse<PageResponse<AuditRecordResponse>> list(@PathVariable long workspaceId,
            @RequestParam(required=false) Long projectId,@RequestParam(required=false) Long actorUserId,
            @RequestParam(required=false) AuditActionType actionType,@RequestParam(required=false) AuditResourceType resourceType,
            @RequestParam(required=false) AuditResult result,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredTo,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success(service.list(workspaceId,projectId,actorUserId,actionType,resourceType,result,
                occurredFrom,occurredTo,page,size));}
    @GetMapping("/{auditId}")
    public ApiResponse<AuditRecordResponse> detail(@PathVariable long workspaceId,@PathVariable long auditId,
                                                   @RequestParam(required=false) Long projectId){
        return ApiResponse.success(service.detail(workspaceId,auditId,projectId));}
}
