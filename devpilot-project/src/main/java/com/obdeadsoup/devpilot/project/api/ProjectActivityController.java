package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityPageResponse;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/activities")
public class ProjectActivityController {

    private final ProjectActivityService activityService;

    public ProjectActivityController(ProjectActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public ApiResponse<ProjectActivityPageResponse> timeline(
            @PathVariable @Min(1) long workspaceId,
            @PathVariable @Min(1) long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(activityService.queryTimeline(workspaceId, projectId, page, size));
    }
}
