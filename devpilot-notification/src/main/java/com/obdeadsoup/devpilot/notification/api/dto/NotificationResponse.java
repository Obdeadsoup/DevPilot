package com.obdeadsoup.devpilot.notification.api.dto;
import java.time.LocalDateTime;
public record NotificationResponse(long id,long workspaceId,long projectId,String type,String title,String content,
 String targetType,long targetId,String targetPath,String sourceType,long sourceId,String status,LocalDateTime readAt,
 LocalDateTime occurredAt,LocalDateTime createdAt,long version) { }
