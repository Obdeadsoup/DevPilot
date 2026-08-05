package com.obdeadsoup.devpilot.notification.api.dto;
import jakarta.validation.constraints.PositiveOrZero;
public record MarkNotificationReadRequest(@PositiveOrZero long expectedVersion) { }
