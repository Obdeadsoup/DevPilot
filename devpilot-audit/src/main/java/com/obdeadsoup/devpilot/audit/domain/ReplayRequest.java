package com.obdeadsoup.devpilot.audit.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplayRequest(
        @NotBlank @Size(min = 10, max = 500) String reason,
        @NotNull Long expectedVersion) {
}
