package com.obdeadsoup.devpilot.notification.application;
import com.obdeadsoup.devpilot.notification.domain.*;
import java.time.LocalDateTime;
public record CreateNotificationCommand(long recipientUserId,long workspaceId,long projectId,NotificationType type,
 String title,String content,NotificationTargetType targetType,long targetId,String targetPath,
 NotificationSourceType sourceType,long sourceId,String dedupeKey,LocalDateTime occurredAt) { }
