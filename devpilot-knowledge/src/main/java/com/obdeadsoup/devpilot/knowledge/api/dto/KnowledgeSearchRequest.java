package com.obdeadsoup.devpilot.knowledge.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 2_000) String query,
        @Size(max = 6) List<@Size(max = 2_000) String> conversationHistory,
        @Min(1) @Max(20) Integer topK
) {
}
