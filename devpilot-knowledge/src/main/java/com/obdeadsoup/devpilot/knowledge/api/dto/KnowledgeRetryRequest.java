package com.obdeadsoup.devpilot.knowledge.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record KnowledgeRetryRequest(@PositiveOrZero long expectedVersion) {
}
