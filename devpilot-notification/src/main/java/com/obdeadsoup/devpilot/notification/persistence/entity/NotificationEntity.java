package com.obdeadsoup.devpilot.notification.persistence.entity;
import java.time.LocalDateTime;
public record NotificationEntity(long id,long recipientUserId,long workspaceId,long projectId,String notificationType,
 String title,String content,String targetType,long targetId,String targetPath,String sourceType,long sourceId,
 String dedupeKey,String status,LocalDateTime readAt,LocalDateTime occurredAt,LocalDateTime createdAt,LocalDateTime updatedAt,long version) { }
