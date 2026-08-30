package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;
import com.obdeadsoup.devpilot.agent.persistence.mapper.AgentRunMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

/**
 * AgentRun 的短事务边界。创建 RUNNING 与写入终态由不同 public 方法提交，
 * 调用方必须在两个方法之间、事务之外执行 Python RPC。
 */
@Service
public class AgentRunPersistenceService implements AgentRunExecutionContextQuery {
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

    /** terminal/cancel 并发时不抛冲突：返回 empty 表示另一个终态已先提交。 */
    @Transactional
    public Optional<AgentRunView> tryMarkSucceeded(long workspaceId, long projectId, String runId,
                                                   String finalOutput, LocalDateTime finishedAt) {
        if (mapper.markSucceeded(workspaceId, projectId, runId, finalOutput, finishedAt, 0) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireByScope(workspaceId, projectId, runId));
    }

    /** terminal/cancel 并发时只允许一个 RUNNING(version=0) 更新获胜。 */
    @Transactional
    public Optional<AgentRunView> tryMarkFailed(long workspaceId, long projectId, String runId,
                                                AgentRunFailureKind failureKind, LocalDateTime finishedAt) {
        if (mapper.markFailed(workspaceId, projectId, runId, failureKind.name(), finishedAt, 0) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireByScope(workspaceId, projectId, runId));
    }

    /** 取消不写 failure_kind；条件更新保证它不会覆盖已完成终态。 */
    @Transactional
    public Optional<AgentRunView> tryMarkCancelled(long workspaceId, long projectId, String runId,
                                                   LocalDateTime finishedAt) {
        if (mapper.markCancelled(workspaceId, projectId, runId, finishedAt, 0) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireByScope(workspaceId, projectId, runId));
    }

    @Transactional(readOnly = true)
    public AgentRunView get(long workspaceId, long projectId, String runId) {
        return requireByScope(workspaceId, projectId, runId);
    }

    /** 历史查询始终受 workspace/project scope 限制，分页参数已由上层完成边界校验。 */
    @Transactional(readOnly = true)
    public List<AgentRunHistoryItem> listHistory(long workspaceId, long projectId,
                                                  AgentRunStatus status, int page, int size) {
        int offset = Math.multiplyExact(page, size);
        return mapper.findHistory(workspaceId, projectId, status == null ? null : status.name(), offset, size)
                .stream().map(AgentRunView::from).map(AgentRunHistoryItem::from).toList();
    }

    @Transactional(readOnly = true)
    public long countHistory(long workspaceId, long projectId, AgentRunStatus status) {
        return mapper.countHistory(workspaceId, projectId, status == null ? null : status.name());
    }

    /** Tool Gateway 只按不可猜测的 runId 恢复 Java 权威 actor/scope，不把用户身份交给 Python 声明。 */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<AgentRunExecutionContext> findByRunIdForRuntime(String runId) {
        return mapper.findByRunId(runId).map(AgentRunExecutionContext::from);
    }

    private AgentRunView requireByScope(long workspaceId, long projectId, String runId) {
        return mapper.findByScope(workspaceId, projectId, runId)
                .map(AgentRunView::from)
                .orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_RUN_NOT_FOUND));
    }
}
