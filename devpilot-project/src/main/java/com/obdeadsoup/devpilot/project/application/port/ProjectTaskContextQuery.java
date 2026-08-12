package com.obdeadsoup.devpilot.project.application.port;

import java.util.Optional;

/** 向 Task 提供带 Workspace 状态校验的只读 Project 上下文边界。 */
public interface ProjectTaskContextQuery {

    Optional<ProjectTaskContext> findByScope(long workspaceId, long projectId);
}
