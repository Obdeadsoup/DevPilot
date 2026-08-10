package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.application.WorkspaceAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.*;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplayAuthorizationServiceTest {
    private final ProjectAuthorizationService projects=mock(ProjectAuthorizationService.class);
    private final WorkspaceAuthorizationService workspaces=mock(WorkspaceAuthorizationService.class);
    private final ReplayAuthorizationService service=new ReplayAuthorizationService(projects,workspaces);

    @Test void projectAdminCanOperateOutboxButDeveloperCannot(){
        when(projects.getEffectiveProjectAccess(1,10,20)).thenReturn(Optional.of(access(ProjectRole.PROJECT_ADMIN)));
        assertThatCode(()->service.requireOutboxAdministration(1,10,20)).doesNotThrowAnyException();
        when(projects.getEffectiveProjectAccess(2,10,20)).thenReturn(Optional.of(access(ProjectRole.DEVELOPER)));
        assertThatThrownBy(()->service.requireOutboxAdministration(2,10,20)).isInstanceOf(BusinessException.class);
    }

    @Test void syncUsesExistingRepositoryUpdatePermission(){
        when(projects.hasPermission(2,10,20,ProjectPermission.REPOSITORY_UPDATE)).thenReturn(true);
        assertThatCode(()->service.requireSyncReplay(2,10,20)).doesNotThrowAnyException();
    }

    @Test void workspaceAdminReadsWorkspaceAuditAndProjectAdminNeedsExplicitScope(){
        when(workspaces.getEffectiveRole(1,10)).thenReturn(Optional.of(WorkspaceRole.ADMIN));
        assertThatCode(()->service.requireAuditRead(1,10,null)).doesNotThrowAnyException();
        when(workspaces.getEffectiveRole(3,10)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
        when(projects.getEffectiveProjectAccess(3,10,20)).thenReturn(Optional.of(access(ProjectRole.PROJECT_ADMIN)));
        assertThatCode(()->service.requireAuditRead(3,10,20L)).doesNotThrowAnyException();
        assertThatThrownBy(()->service.requireAuditRead(3,10,null)).isInstanceOf(BusinessException.class);
    }

    private ProjectAccess access(ProjectRole role){return new ProjectAccess(role,ProjectAccess.Source.PROJECT_MEMBERSHIP,role.permissions());}
}
