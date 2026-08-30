package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import org.springframework.stereotype.Service;

/**
 * AgentRun HTTP 编排：校验权限、提交 RUNNING，再在事务外启动异步 Streaming。
 */
@Service
public class AgentRunApplicationService {
    public static final int MAX_INPUT_LENGTH = 10_000;

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService authorizationService;
    private final AgentRunPersistenceService persistenceService;
    private final AgentRunStreamCoordinator streamCoordinator;
    private final AgentRunIdentityFactory identityFactory;
    private final AgentRunTimeProvider timeProvider;
    private final AgentRunCancellationMetrics cancellationMetrics;

    public AgentRunApplicationService(CurrentUserProvider currentUserProvider,
                                      ProjectAuthorizationService authorizationService,
                                      AgentRunPersistenceService persistenceService,
                                      AgentRunStreamCoordinator streamCoordinator,
                                      AgentRunIdentityFactory identityFactory,
                                      AgentRunTimeProvider timeProvider,
                                      AgentRunCancellationMetrics cancellationMetrics) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.persistenceService = persistenceService;
        this.streamCoordinator = streamCoordinator;
        this.identityFactory = identityFactory;
        this.timeProvider = timeProvider;
        this.cancellationMetrics = cancellationMetrics;
    }

    /**
     * 提交 RUNNING 后启动异步流并立即返回初态；终态由 Coordinator callback 的另一短事务写入。
     */
    public AgentRunView start(long workspaceId, long projectId, String input) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_PROPOSE);
        String normalizedInput = normalizeInput(input);
        AgentRunIdentity identity = identityFactory.create();
        AgentRunView running = persistenceService.createRunning(
                identity.requestId(), identity.runId(), workspaceId, projectId,
                userId, normalizedInput, timeProvider.now());
        streamCoordinator.start(workspaceId, projectId,
                new AgentRunCommand(identity.requestId(), identity.runId(), normalizedInput));
        return running;
    }

    /** 查询始终携带 workspace/project scope，并复用 AGENT_READ 权限。 */
    public AgentRunView get(long workspaceId, long projectId, String runId) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_READ);
        return persistenceService.get(workspaceId, projectId, runId);
    }

    /** 运行历史复用 AGENT_READ；列表与详情使用相同 Project scope，避免跨项目 runId 枚举。 */
    public PageResponse<AgentRunHistoryItem> listHistory(long workspaceId, long projectId,
                                                          AgentRunStatus status, int page, int size) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_READ);
        return new PageResponse<>(page, size,
                persistenceService.countHistory(workspaceId, projectId, status),
                persistenceService.listHistory(workspaceId, projectId, status, page, size));
    }

    /**
     * 先确认本地 RUNNING，再请求 Python 协作取消。重复取消直接返回 CANCELLED；
     * 已成功或失败的 run 不允许倒退，Runtime 不可达时也不伪造 CANCELLED。
     */
    public AgentRunView cancel(long workspaceId, long projectId, String runId) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_PROPOSE);
        AgentRunView current = persistenceService.get(workspaceId, projectId, runId);
        if (current.status() == AgentRunStatus.CANCELLED) {
            return current;
        }
        if (current.status() != AgentRunStatus.RUNNING) {
            throw new BusinessException(AgentRunErrorCode.AGENT_RUN_ALREADY_TERMINAL);
        }
        cancellationMetrics.requested();
        try {
            AgentRunView cancelled = streamCoordinator.cancel(workspaceId, projectId, current);
            cancellationMetrics.accepted();
            return cancelled;
        } catch (AgentRuntimeCancellationException exception) {
            cancellationMetrics.failed();
            throw new BusinessException(AgentRunErrorCode.AGENT_RUN_CANCEL_FAILED);
        }
    }

    private String normalizeInput(String input) {
        if (input == null) {
            throw new BusinessException(AgentRunErrorCode.INVALID_AGENT_INPUT);
        }
        String normalized = input.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_INPUT_LENGTH) {
            throw new BusinessException(AgentRunErrorCode.INVALID_AGENT_INPUT);
        }
        return normalized;
    }
}
