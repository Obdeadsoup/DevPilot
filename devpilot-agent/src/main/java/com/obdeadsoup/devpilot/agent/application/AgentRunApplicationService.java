package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeClientException;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.springframework.stereotype.Service;

/**
 * AgentRun 真实调用链编排：校验当前用户与 Project 权限，提交 RUNNING，事务外调用 Runtime，
 * 再提交 SUCCEEDED/FAILED。该服务故意不加事务，避免数据库连接跨越网络等待。
 */
@Service
public class AgentRunApplicationService {
    public static final int MAX_INPUT_LENGTH = 10_000;

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService authorizationService;
    private final AgentRunPersistenceService persistenceService;
    private final AgentRuntimePort runtimePort;
    private final AgentRunIdentityFactory identityFactory;
    private final AgentRunTimeProvider timeProvider;

    public AgentRunApplicationService(CurrentUserProvider currentUserProvider,
                                      ProjectAuthorizationService authorizationService,
                                      AgentRunPersistenceService persistenceService,
                                      AgentRuntimePort runtimePort,
                                      AgentRunIdentityFactory identityFactory,
                                      AgentRunTimeProvider timeProvider) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.persistenceService = persistenceService;
        this.runtimePort = runtimePort;
        this.identityFactory = identityFactory;
        this.timeProvider = timeProvider;
    }

    /**
     * 同步启动一次 Agent Run。已分类的 RPC 失败会作为 FAILED 投影正常返回，调用者可用 runId 再查询；
     * Deadline 只表示 Java 停止等待，不能据此断言 Python 没有继续执行。
     */
    public AgentRunView start(long workspaceId, long projectId, String input) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_PROPOSE);
        String normalizedInput = normalizeInput(input);
        AgentRunIdentity identity = identityFactory.create();
        persistenceService.createRunning(identity.requestId(), identity.runId(), workspaceId, projectId,
                userId, normalizedInput, timeProvider.now());

        try {
            AgentRunResult result = runtimePort.run(
                    new AgentRunCommand(identity.requestId(), identity.runId(), normalizedInput));
            if (result.status() == AgentRunStatus.SUCCEEDED) {
                return persistenceService.markSucceeded(workspaceId, projectId, identity.runId(),
                        result.finalOutput(), timeProvider.now());
            }
            return persistenceService.markFailed(workspaceId, projectId, identity.runId(),
                    AgentRunFailureKind.REMOTE_FAILED, timeProvider.now());
        } catch (AgentRuntimeClientException exception) {
            return persistenceService.markFailed(workspaceId, projectId, identity.runId(),
                    AgentRunFailureKind.fromRuntime(exception.kind()), timeProvider.now());
        } catch (RuntimeException exception) {
            try {
                persistenceService.markFailed(workspaceId, projectId, identity.runId(),
                        AgentRunFailureKind.UNKNOWN, timeProvider.now());
            } catch (RuntimeException projectionFailure) {
                exception.addSuppressed(projectionFailure);
            }
            throw exception;
        }
    }

    /** 查询始终携带 workspace/project scope，并复用 AGENT_READ 权限。 */
    public AgentRunView get(long workspaceId, long projectId, String runId) {
        long userId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(userId, workspaceId, projectId, ProjectPermission.AGENT_READ);
        return persistenceService.get(workspaceId, projectId, runId);
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
