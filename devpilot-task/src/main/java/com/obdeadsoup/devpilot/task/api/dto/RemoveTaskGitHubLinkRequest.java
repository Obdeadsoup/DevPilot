package com.obdeadsoup.devpilot.task.api.dto;
import jakarta.validation.constraints.Min;
public record RemoveTaskGitHubLinkRequest(@Min(0) long expectedTaskVersion,@Min(0) long expectedLinkVersion) { }
