package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.identity.application.WorkspaceProjectMembershipRevoker;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectWorkspaceMembershipRevoker implements WorkspaceProjectMembershipRevoker {

    private final ProjectMemberMapper projectMemberMapper;

    public ProjectWorkspaceMembershipRevoker(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }

    @Override
    @Transactional
    public void revokeAllForWorkspaceUser(long workspaceId, long userId) {
        projectMemberMapper.removeAllForWorkspaceUser(workspaceId, userId);
    }
}
