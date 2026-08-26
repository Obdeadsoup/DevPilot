package com.obdeadsoup.devpilot.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StartAgentRunRequest(
        @NotBlank(message = "input must not be blank")
        @Size(max = 10_000, message = "input length must be at most 10000")
        String input
) {
}
