package com.obdeadsoup.devpilot.github.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record GitHubRepositoryVersionRequest(
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
