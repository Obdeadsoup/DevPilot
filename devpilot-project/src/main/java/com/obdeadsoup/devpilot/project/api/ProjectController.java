package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.project.api.dto.CreateProjectRequest;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.api.dto.UpdateProjectRequest;
import com.obdeadsoup.devpilot.project.api.dto.VersionRequest;
import com.obdeadsoup.devpilot.project.application.ProjectService;
import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
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
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(
            @PathVariable @Positive long workspaceId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        ProjectResponse project = projectService.createProject(
                workspaceId,
                request.name(),
                request.projectKey(),
                request.description(),
                request.visibility()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(project));
    }

    @GetMapping
    public ApiResponse<PageResponse<ProjectResponse>> list(
            @PathVariable @Positive long workspaceId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) ProjectVisibility visibility
    ) {
        return ApiResponse.success(projectService.listProjects(
                workspaceId, page, size, status, visibility
        ));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> get(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId
    ) {
        return ApiResponse.success(projectService.getProject(workspaceId, projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectResponse> updateProfile(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return ApiResponse.success(projectService.updateProjectProfile(
                workspaceId,
                projectId,
                request.name(),
                request.description(),
                request.visibility(),
                request.expectedVersion()
        ));
    }

    @PostMapping("/{projectId}/activate")
    public ApiResponse<ProjectResponse> activate(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody VersionRequest request
    ) {
        return ApiResponse.success(projectService.activateProject(
                workspaceId, projectId, request.expectedVersion()
        ));
    }

    @PostMapping("/{projectId}/archive")
    public ApiResponse<ProjectResponse> archive(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody VersionRequest request
    ) {
        return ApiResponse.success(projectService.archiveProject(
                workspaceId, projectId, request.expectedVersion()
        ));
    }

    @PostMapping("/{projectId}/restore")
    public ApiResponse<ProjectResponse> restore(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long projectId,
            @Valid @RequestBody VersionRequest request
    ) {
        return ApiResponse.success(projectService.restoreProject(
                workspaceId, projectId, request.expectedVersion()
        ));
    }
}
