package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.domain.TaskAction;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.domain.TaskTransition;
import com.obdeadsoup.devpilot.task.domain.TaskTransitionPolicy;
import com.obdeadsoup.devpilot.task.application.outbox.TaskOutboxEventFactory;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;

/** 显式执行本地 Task 状态动作；PR MERGED、Issue CLOSED 和 GitHub Review 都不会自动调用这里。 */
@Service
@Transactional
public class TaskWorkflowService {
    private final TaskApplicationService taskApplicationService; private final TaskMapper taskMapper;
    private final TaskAuthorizationService authorizationService; private final TaskTransitionPolicy transitionPolicy;
    private final TaskPersistenceService persistenceService; private final CurrentUserProvider currentUserProvider; private final Clock clock;
    private final TaskOutboxEventFactory outboxEvents;
    public TaskWorkflowService(TaskApplicationService taskApplicationService, TaskMapper taskMapper, TaskAuthorizationService authorizationService,
                               TaskTransitionPolicy transitionPolicy, TaskPersistenceService persistenceService, CurrentUserProvider currentUserProvider, Clock taskClock,
                               TaskOutboxEventFactory outboxEvents) {
        this.taskApplicationService=taskApplicationService; this.taskMapper=taskMapper; this.authorizationService=authorizationService;
        this.transitionPolicy=transitionPolicy; this.persistenceService=persistenceService; this.currentUserProvider=currentUserProvider; this.clock=taskClock;
        this.outboxEvents=outboxEvents;
    }
    public TaskResponse planTask(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.PLANNED);}
    public TaskResponse returnToBacklog(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.RETURNED_TO_BACKLOG);}
    public TaskResponse startTask(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.STARTED);}
    public TaskResponse submitForReview(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.SUBMITTED_FOR_REVIEW);}
    public TaskResponse requestChanges(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.CHANGES_REQUESTED);}
    public TaskResponse completeTask(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.COMPLETED);}
    public TaskResponse cancelTask(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.CANCELED);}
    public TaskResponse reopenTask(long w,long p,long id,long v,String reason){return perform(w,p,id,v,reason,TaskAction.REOPENED);}

    /** 每个动作都以 expectedVersion 条件 UPDATE 抢占一次状态迁移，并同事务写 History 与 Activity。 */
    private TaskResponse perform(long workspaceId,long projectId,long taskId,long expectedVersion,String reason,TaskAction action) {
        String projectKey=taskApplicationService.requireWritableProject(workspaceId,projectId);
        TaskEntity task=taskApplicationService.requireTask(workspaceId,projectId,taskId); long actor=currentUserProvider.requireUserId();
        authorizationService.requireWorkflow(actor,task,action); validateReason(reason);
        TaskTransition transition=transitionPolicy.transition(TaskStatus.valueOf(task.getStatus()),action); LocalDateTime now=LocalDateTime.now(clock);
        if(taskMapper.transition(workspaceId,projectId,taskId,transition.from().name(),transition.to().name(),
                transition.completes()?now:null,transition.cancels()?now:null,expectedVersion)!=1) {
            throw taskApplicationService.writeConflict(workspaceId,projectId,taskId,expectedVersion);
        }
        persistenceService.recordTransition(task,transition,actor,normalizeReason(reason),expectedVersion+1,now);
        outboxEvents.publishWorkflow(task,projectKey,expectedVersion+1,actor,action,now);
        return TaskResponse.from(taskApplicationService.requireTask(workspaceId,projectId,taskId),projectKey,true);
    }
    private void validateReason(String reason){ if(reason!=null && reason.trim().length()>1000) throw new BusinessException(TaskErrorCode.INVALID_TASK_REASON); }
    private String normalizeReason(String reason){ return reason==null||reason.isBlank()?null:reason.trim(); }
}
