package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
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

    public AgentRunApplicationService(CurrentUserProvider currentUserProvider,
                                      ProjectAuthorizationService authorizationService,
                                      AgentRunPersistenceService persistenceService,
                                      AgentRunStreamCoordinator streamCoordinator,
                                      AgentRunIdentityFactory identityFactory,
                                      AgentRunTimeProvider timeProvider) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.persistenceService = persistenceService;
        this.streamCoordinator = streamCoordinator;
        this.identityFactory = identityFactory;
        this.timeProvider = timeProvider;
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
