package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.project.api.dto.CreateWorkspaceRequest;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.api.dto.UpdateWorkspaceRequest;
import com.obdeadsoup.devpilot.project.api.dto.VersionRequest;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceResponse;
import com.obdeadsoup.devpilot.project.application.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> create(
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        WorkspaceResponse workspace = workspaceService.createWorkspace(
                request.name(), request.slug(), request.description()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(workspace));
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkspaceResponse>> listMine(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(workspaceService.listMyWorkspaces(page, size));
    }

    @GetMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> get(
            @PathVariable @Positive long workspaceId
    ) {
        return ApiResponse.success(workspaceService.getWorkspace(workspaceId));
    }

    @PutMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> updateProfile(
            @PathVariable @Positive long workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request
    ) {
        return ApiResponse.success(workspaceService.updateWorkspaceProfile(
                workspaceId,
                request.name(),
                request.description(),
                request.expectedVersion()
        ));
    }

    @PostMapping("/{workspaceId}/disable")
    public ApiResponse<WorkspaceResponse> disable(
            @PathVariable @Positive long workspaceId,
            @Valid @RequestBody VersionRequest request
    ) {
        return ApiResponse.success(workspaceService.disableWorkspace(
                workspaceId, request.expectedVersion()
        ));
    }

    @PostMapping("/{workspaceId}/reactivate")
    public ApiResponse<WorkspaceResponse> reactivate(
            @PathVariable @Positive long workspaceId,
            @Valid @RequestBody VersionRequest request
    ) {
        return ApiResponse.success(workspaceService.reactivateWorkspace(
                workspaceId, request.expectedVersion()
        ));
    }
}
