package com.obdeadsoup.devpilot.task.api.dto;
import jakarta.validation.constraints.Min; import jakarta.validation.constraints.Size;
public record TaskActionRequest(@Min(0) long expectedVersion,@Size(max=1000) String reason) { }
