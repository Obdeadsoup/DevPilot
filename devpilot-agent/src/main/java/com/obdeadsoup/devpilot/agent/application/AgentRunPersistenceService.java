package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;
import com.obdeadsoup.devpilot.agent.persistence.mapper.AgentRunMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AgentRun 的短事务边界。创建 RUNNING 与写入终态由不同 public 方法提交，
 * 调用方必须在两个方法之间、事务之外执行 Python RPC。
 */
@Service
public class AgentRunPersistenceService {
    private final AgentRunMapper mapper;

    public AgentRunPersistenceService(AgentRunMapper mapper) {
        this.mapper = mapper;
    }

    /** 先持久化可查询的 RUNNING 投影；唯一键冲突会转换成稳定业务错误。 */
    @Transactional
    public AgentRunView createRunning(String requestId, String runId, long workspaceId, long projectId,
                                      long createdBy, String userInput, LocalDateTime startedAt) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRequestId(requestId);
        entity.setRunId(runId);
        entity.setWorkspaceId(workspaceId);
        entity.setProjectId(projectId);
        entity.setCreatedBy(createdBy);
        entity.setStatus(AgentRunStatus.RUNNING.name());
        entity.setUserInput(userInput);
        entity.setStartedAt(startedAt);
        entity.setVersion(0);
        entity.setDeleted(false);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(AgentRunErrorCode.AGENT_RUN_ID_CONFLICT);
        }
        return requireByScope(workspaceId, projectId, runId);
    }

    /** 仅允许 version=0 的 RUNNING 投影成功一次，防止并发终态相互覆盖。 */
    @Transactional
    public AgentRunView markSucceeded(long workspaceId, long projectId, String runId,
                                      String finalOutput, LocalDateTime finishedAt) {
        if (mapper.markSucceeded(workspaceId, projectId, runId, finalOutput, finishedAt, 0) != 1) {
            throw new BusinessException(AgentRunErrorCode.AGENT_RUN_STATE_CONFLICT);
        }
        return requireByScope(workspaceId, projectId, runId);
    }

    /** 失败投影只保存稳定 failureKind，不保存远端原始消息、payload 或堆栈。 */
    @Transactional
    public AgentRunView markFailed(long workspaceId, long projectId, String runId,
                                   AgentRunFailureKind failureKind, LocalDateTime finishedAt) {
        if (mapper.markFailed(workspaceId, projectId, runId, failureKind.name(), finishedAt, 0) != 1) {
            throw new BusinessException(AgentRunErrorCode.AGENT_RUN_STATE_CONFLICT);
        }
        return requireByScope(workspaceId, projectId, runId);
    }

    @Transactional(readOnly = true)
    public AgentRunView get(long workspaceId, long projectId, String runId) {
        return requireByScope(workspaceId, projectId, runId);
    }

    private AgentRunView requireByScope(long workspaceId, long projectId, String runId) {
        return mapper.findByScope(workspaceId, projectId, runId)
                .map(AgentRunView::from)
                .orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_RUN_NOT_FOUND));
    }
}
