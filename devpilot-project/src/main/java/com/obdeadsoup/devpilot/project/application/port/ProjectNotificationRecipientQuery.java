package com.obdeadsoup.devpilot.project.application.port;

import java.util.Set;

/** 解析本地 Project 管理接收人；Notification 不复制角色矩阵，也不能用通知反推资源权限。 */
public interface ProjectNotificationRecipientQuery {
    Set<Long> findManagerUserIds(long workspaceId, long projectId);
    boolean isActiveRecipientInScope(long userId, long workspaceId, long projectId);
}
