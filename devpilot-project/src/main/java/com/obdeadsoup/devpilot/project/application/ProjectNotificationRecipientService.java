package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashSet;
import java.util.Set;

/** 用 Workspace Owner、ACTIVE ADMIN 与 ACTIVE PROJECT_ADMIN 计算接收人并去重。 */
@Service
public class ProjectNotificationRecipientService implements ProjectNotificationRecipientQuery {
    private final ProjectMapper mapper;
    public ProjectNotificationRecipientService(ProjectMapper mapper){this.mapper=mapper;}
    public Set<Long> findManagerUserIds(long w,long p){
        if(mapper.findByScope(w,p).isEmpty()) throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        return new LinkedHashSet<>(mapper.findNotificationManagers(w,p));
    }
    public boolean isActiveRecipientInScope(long u,long w,long p){ return mapper.countActiveNotificationRecipient(u,w,p)>0; }
}
