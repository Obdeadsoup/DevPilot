package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @NotNull ProjectVisibility visibility,
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
