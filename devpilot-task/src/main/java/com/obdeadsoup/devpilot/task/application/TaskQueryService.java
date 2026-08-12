package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContextQuery;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.task.api.dto.*;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskStatusHistoryMapper;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime; import java.util.List;

/** Task 查询在授权后把过滤条件直接下推到 scoped SQL；列表不返回 description 或完整 history。 */
@Service
public class TaskQueryService {
    private final TaskMapper taskMapper; private final TaskStatusHistoryMapper historyMapper; private final ProjectTaskContextQuery projectTaskContextQuery;
    private final TaskAuthorizationService authorizationService; private final CurrentUserProvider currentUserProvider;
    public TaskQueryService(TaskMapper taskMapper,TaskStatusHistoryMapper historyMapper,ProjectTaskContextQuery projectTaskContextQuery,TaskAuthorizationService authorizationService,CurrentUserProvider currentUserProvider){this.taskMapper=taskMapper;this.historyMapper=historyMapper;this.projectTaskContextQuery=projectTaskContextQuery;this.authorizationService=authorizationService;this.currentUserProvider=currentUserProvider;}
    @Transactional(readOnly=true)
    public PageResponse<TaskResponse> list(long w,long p,int page,int size,TaskStatus status,TaskPriority priority,Long assignee,Long reporter,LocalDateTime dueBefore){
        authorizationService.requirePermission(currentUserProvider.requireUserId(),w,p,ProjectPermission.TASK_READ); String projectKey=requireProjectKey(w,p);
        long total=taskMapper.countPage(w,p,status==null?null:status.name(),priority==null?null:priority.name(),assignee,reporter,dueBefore);
        List<TaskResponse> items=taskMapper.findPage(w,p,status==null?null:status.name(),priority==null?null:priority.name(),assignee,reporter,dueBefore,(long)(page-1)*size,size).stream().map(t->TaskResponse.from(t,projectKey,false)).toList();
        return new PageResponse<>(page,size,total,items);
    }
    @Transactional(readOnly=true)
    public TaskDetailResponse get(long w,long p,long taskId){
        authorizationService.requirePermission(currentUserProvider.requireUserId(),w,p,ProjectPermission.TASK_READ); String projectKey=requireProjectKey(w,p);
        TaskEntity task=taskMapper.findByScope(w,p,taskId).orElseThrow(()->new com.obdeadsoup.devpilot.framework.error.BusinessException(com.obdeadsoup.devpilot.task.error.TaskErrorCode.TASK_NOT_FOUND));
        return new TaskDetailResponse(TaskResponse.from(task,projectKey,true),historyMapper.findByTaskScope(w,p,taskId).stream().map(TaskStatusHistoryResponse::from).toList());
    }
    private String requireProjectKey(long workspaceId,long projectId){
        return projectTaskContextQuery.findByScope(workspaceId,projectId).map(context->context.projectKey())
                .orElseThrow(()->new com.obdeadsoup.devpilot.framework.error.BusinessException(com.obdeadsoup.devpilot.task.error.TaskErrorCode.TASK_NOT_FOUND));
    }
}
