package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.WorkspaceMemberStatus;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceMemberEntity;

import java.time.LocalDateTime;

/** Workspace 成员投影；用户身份只以本地 userId 表示，邮箱不在列表 API 暴露。 */
public record WorkspaceMemberResponse(long id, long userId, WorkspaceRole role,
                                      WorkspaceMemberStatus status, long invitedBy,
                                      LocalDateTime joinedAt, long version) {
    public static WorkspaceMemberResponse from(WorkspaceMemberEntity entity) {
        return new WorkspaceMemberResponse(entity.id(), entity.userId(), WorkspaceRole.valueOf(entity.role()),
                WorkspaceMemberStatus.valueOf(entity.status()), entity.invitedBy(), entity.joinedAt(), entity.version());
    }
}
