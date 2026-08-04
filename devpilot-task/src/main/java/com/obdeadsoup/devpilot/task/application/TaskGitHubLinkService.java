package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import com.obdeadsoup.devpilot.task.api.dto.TaskGitHubLinkResponse;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.command.CreateTaskFromIssueCommand;
import com.obdeadsoup.devpilot.task.application.port.TaskExternalReferenceSnapshot;
import com.obdeadsoup.devpilot.task.application.port.TaskGitHubReferenceReader;
import com.obdeadsoup.devpilot.task.domain.TaskGitHubRelationType;
import com.obdeadsoup.devpilot.task.domain.TaskGitHubResourceType;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskGitHubLinkEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskGitHubLinkMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 显式维护 Task 与 GitHub Snapshot 的关联。客户端只能提交本地 Snapshot ID；Adapter 回填稳定对象 ID，
 * 所以 URL/number/外部 login 都不能伪造关联。PR MERGED 只是快照状态，绝不自动把 Task 置为 DONE。
 */
@Service
@Transactional
public class TaskGitHubLinkService {
    private final TaskApplicationService taskApplicationService; private final TaskMapper taskMapper;
    private final TaskGitHubLinkMapper linkMapper; private final TaskGitHubReferenceReader referenceReader;
    private final TaskAuthorizationService authorizationService; private final TaskPersistenceService persistenceService;
    private final CurrentUserProvider currentUserProvider; private final Clock clock;
    public TaskGitHubLinkService(TaskApplicationService taskApplicationService, TaskMapper taskMapper, TaskGitHubLinkMapper linkMapper,
                                 TaskGitHubReferenceReader referenceReader, TaskAuthorizationService authorizationService,
                                 TaskPersistenceService persistenceService, CurrentUserProvider currentUserProvider, Clock taskClock) {
        this.taskApplicationService=taskApplicationService; this.taskMapper=taskMapper; this.linkMapper=linkMapper;
        this.referenceReader=referenceReader; this.authorizationService=authorizationService; this.persistenceService=persistenceService;
        this.currentUserProvider=currentUserProvider; this.clock=taskClock;
    }
    public TaskResponse linkIssue(long w,long p,long taskId,long issueSnapshotId,long expectedVersion,TaskGitHubRelationType relation) {
        return link(w,p,taskId,expectedVersion,referenceReader.readIssue(w,p,issueSnapshotId),relation==null?TaskGitHubRelationType.TRACKS:relation);
    }
    public TaskResponse linkPullRequest(long w,long p,long taskId,long prSnapshotId,long expectedVersion,TaskGitHubRelationType relation) {
        return link(w,p,taskId,expectedVersion,referenceReader.readPullRequest(w,p,prSnapshotId),relation==null?TaskGitHubRelationType.IMPLEMENTED_BY:relation);
    }

    public TaskResponse link(long workspaceId,long projectId,long taskId,long expectedVersion,
                             TaskExternalReferenceSnapshot reference,TaskGitHubRelationType relation) {
        ProjectEntity project=taskApplicationService.requireWritableProject(workspaceId,projectId);
        TaskEntity task=taskApplicationService.requireTask(workspaceId,projectId,taskId); long actor=currentUserProvider.requireUserId();
        authorizationService.requireLinkUpdate(actor,task);
        if(reference.workspaceId()!=workspaceId || reference.projectId()!=projectId) throw new BusinessException(TaskErrorCode.TASK_EXTERNAL_REFERENCE_SCOPE_MISMATCH);
        try {
            linkMapper.insert(workspaceId,projectId,taskId,reference.repositoryBindingId(),reference.githubRepositoryId(),reference.resourceType().name(),
                    relation.name(),reference.resourceType()==TaskGitHubResourceType.ISSUE?reference.localSnapshotId():null,
                    reference.resourceType()==TaskGitHubResourceType.PULL_REQUEST?reference.localSnapshotId():null,
                    reference.githubObjectId(),reference.githubNumber(),actor);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(TaskErrorCode.TASK_EXTERNAL_RESOURCE_ALREADY_LINKED);
        }
        boolean manager=authorizationService.isManager(actor,workspaceId,projectId);
        if(taskMapper.incrementVersionForLink(workspaceId,projectId,taskId,expectedVersion,manager)!=1) {
            throw taskApplicationService.writeConflict(workspaceId,projectId,taskId,expectedVersion);
        }
        persistenceService.recordLink(task,expectedVersion+1,false,LocalDateTime.now(clock));
        return TaskResponse.from(taskApplicationService.requireTask(workspaceId,projectId,taskId),project.projectKey(),true);
    }

    public TaskResponse removeGitHubLink(long workspaceId,long projectId,long taskId,long linkId,long expectedTaskVersion,long expectedLinkVersion) {
        ProjectEntity project=taskApplicationService.requireWritableProject(workspaceId,projectId);
        TaskEntity task=taskApplicationService.requireTask(workspaceId,projectId,taskId); long actor=currentUserProvider.requireUserId();
        authorizationService.requireLinkUpdate(actor,task);
        TaskGitHubLinkEntity link=linkMapper.findByTaskScopeAndId(workspaceId,projectId,taskId,linkId)
                .orElseThrow(()->new BusinessException(TaskErrorCode.TASK_LINK_NOT_FOUND));
        if(linkMapper.remove(workspaceId,projectId,taskId,linkId,actor,expectedLinkVersion)!=1) throw new BusinessException(TaskErrorCode.TASK_LINK_VERSION_CONFLICT);
        boolean manager=authorizationService.isManager(actor,workspaceId,projectId);
        if(taskMapper.incrementVersionForLink(workspaceId,projectId,taskId,expectedTaskVersion,manager)!=1) throw taskApplicationService.writeConflict(workspaceId,projectId,taskId,expectedTaskVersion);
        persistenceService.recordLink(task,expectedTaskVersion+1,true,LocalDateTime.now(clock));
        return TaskResponse.from(taskApplicationService.requireTask(workspaceId,projectId,taskId),project.projectKey(),true);
    }

    /**
     * 从 Issue 创建 Task 是人工发起的显式动作，不会把仓库全部 Issue 自动导入。ACTIVE 外部唯一键保证并发下
     * 最多一个有效关联；冲突时整个新 Task/History/Activity 事务回滚并返回稳定 409。
     */
    public TaskResponse createTaskFromIssue(long workspaceId,long projectId,long issueSnapshotId,CreateTaskFromIssueCommand command) {
        long actor=currentUserProvider.requireUserId(); ProjectEntity project=taskApplicationService.requireWritableProject(workspaceId,projectId);
        authorizationService.requirePermission(actor,workspaceId,projectId,ProjectPermission.TASK_CREATE);
        TaskExternalReferenceSnapshot issue=referenceReader.readIssue(workspaceId,projectId,issueSnapshotId);
        if(command.assigneeUserId()!=null) authorizationService.requireEligibleAssignee(command.assigneeUserId(),workspaceId,projectId);
        LocalDateTime now=LocalDateTime.now(clock);
        TaskEntity task=taskApplicationService.preparedTask(workspaceId,projectId,actor,issue.title(),"关联 GitHub Issue #"+issue.githubNumber(),
                command.priority(),command.assigneeUserId(),command.dueAt(),now);
        task= persistenceService.create(task,actor,now);
        try {
            linkMapper.insert(workspaceId,projectId,task.getId(),issue.repositoryBindingId(),issue.githubRepositoryId(),TaskGitHubResourceType.ISSUE.name(),
                    TaskGitHubRelationType.TRACKS.name(),issue.localSnapshotId(),null,issue.githubObjectId(),issue.githubNumber(),actor);
        } catch (DuplicateKeyException exception) { throw new BusinessException(TaskErrorCode.TASK_EXTERNAL_RESOURCE_ALREADY_LINKED); }
        if(taskMapper.incrementVersionForLink(workspaceId,projectId,task.getId(),0,false)!=1) throw new BusinessException(TaskErrorCode.TASK_VERSION_CONFLICT);
        persistenceService.recordLink(task,1,false,now);
        return TaskResponse.from(taskApplicationService.requireTask(workspaceId,projectId,task.getId()),project.projectKey(),true);
    }

    @Transactional(readOnly=true)
    public List<TaskGitHubLinkResponse> listGitHubLinks(long workspaceId,long projectId,long taskId) {
        long actor=currentUserProvider.requireUserId(); TaskEntity task=taskApplicationService.requireTask(workspaceId,projectId,taskId);
        authorizationService.requirePermission(actor,workspaceId,projectId,ProjectPermission.TASK_READ);
        return linkMapper.findByTaskScope(workspaceId,projectId,taskId).stream().map(TaskGitHubLinkResponse::from).toList();
    }
}
