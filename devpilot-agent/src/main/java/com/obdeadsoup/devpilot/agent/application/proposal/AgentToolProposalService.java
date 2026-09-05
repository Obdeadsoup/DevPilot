package com.obdeadsoup.devpilot.agent.application.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.agent.application.*;
import com.obdeadsoup.devpilot.agent.application.tool.*;
import com.obdeadsoup.devpilot.agent.config.AgentProposalProperties;
import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.persistence.entity.AgentToolProposalEntity;
import com.obdeadsoup.devpilot.agent.persistence.mapper.AgentToolProposalMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.TaskApplicationService;
import com.obdeadsoup.devpilot.task.application.TaskAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Proposal 是 exact payload、用户决议和副作用幂等性的 Java 权威记录。 */
@Service
public class AgentToolProposalService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final AgentToolProposalMapper mapper;
    private final AgentRunExecutionContextQuery contextQuery;
    private final AgentRunPersistenceService runPersistence;
    private final ProjectAuthorizationService projectAuthorization;
    private final TaskAuthorizationService taskAuthorization;
    private final TaskApplicationService taskService;
    private final AgentProposalProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public AgentToolProposalService(AgentToolProposalMapper mapper,
                                    AgentRunExecutionContextQuery contextQuery,
                                    AgentRunPersistenceService runPersistence,
                                    ProjectAuthorizationService projectAuthorization,
                                    TaskAuthorizationService taskAuthorization,
                                    TaskApplicationService taskService,
                                    AgentProposalProperties properties,
                                    Clock clock) {
        this.mapper = mapper; this.contextQuery = contextQuery; this.runPersistence = runPersistence;
        this.projectAuthorization = projectAuthorization; this.taskAuthorization = taskAuthorization;
        this.taskService = taskService; this.properties = properties; this.clock = clock;
    }

    /** 插入 Proposal 与 Java Run→WAITING_APPROVAL 同事务；相同 call 只能对应相同 payload。 */
    @Transactional
    public AgentToolProposalView create(CreateAgentToolProposalCommand command) {
        AgentRunExecutionContext context = requireContextAny(command.runId(), command.requestId());
        AgentToolName tool = AgentToolName.fromWireName(command.toolName())
                .orElseThrow(() -> new AgentToolException(AgentToolErrorKind.UNKNOWN_TOOL));
        if (tool.risk() != AgentToolRisk.WRITE_REQUIRES_APPROVAL || tool != AgentToolName.TASK_CREATE) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        requireIdentifier(command.toolCallId());
        TaskCreateProposalPayload payload = TaskCreateProposalPayload.from(command.arguments());
        Map<String, Object> canonical = canonical(payload);
        String canonicalJson = write(canonical);
        String hash = sha256(canonicalJson);
        Optional<AgentToolProposalEntity> existing = mapper.findByCall(command.runId(), command.toolCallId());
        if (existing.isPresent()) {
            if (!existing.get().getPayloadHash().equals(hash)) {
                throw new AgentToolException(AgentToolErrorKind.PROTOCOL);
            }
            return view(existing.get());
        }
        if (context.status() != AgentRunStatus.RUNNING) {
            throw new AgentToolException(AgentToolErrorKind.RUN_NOT_ACTIVE);
        }
        taskAuthorization.requirePermission(context.createdBy(), context.workspaceId(), context.projectId(),
                ProjectPermission.TASK_CREATE);
        if (payload.assigneeUserId() != null) {
            taskAuthorization.requireEligibleAssignee(payload.assigneeUserId(), context.workspaceId(), context.projectId());
        }
        String proposalId = UUID.randomUUID().toString();
        AgentToolProposalEntity entity = new AgentToolProposalEntity();
        entity.setProposalId(proposalId); entity.setRunId(context.runId()); entity.setActorId(context.createdBy());
        entity.setWorkspaceId(context.workspaceId()); entity.setProjectId(context.projectId());
        entity.setToolCallId(command.toolCallId()); entity.setToolName(tool.wireName());
        entity.setCanonicalArguments(canonicalJson); entity.setPayloadHash(hash);
        entity.setIdempotencyKey("proposal:" + proposalId); entity.setStatus(AgentToolProposalStatus.PENDING_APPROVAL.name());
        entity.setExpiresAt(LocalDateTime.now(clock).plus(properties.ttl())); entity.setVersion(0);
        mapper.insert(entity);
        runPersistence.markWaitingApproval(context.runId(), context.version());
        return view(mapper.findById(proposalId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public AgentToolProposalView getForRuntime(String runId, String requestId, String proposalId) {
        requireContextAny(runId, requestId);
        AgentToolProposalEntity entity = require(proposalId);
        if (!entity.getRunId().equals(runId)) throw new AgentToolException(AgentToolErrorKind.NOT_FOUND);
        return view(entity);
    }

    @Transactional(readOnly = true)
    public AgentToolProposalView getForUser(long actor, long workspaceId, long projectId,
                                            String runId, String proposalId) {
        projectAuthorization.requirePermission(actor, workspaceId, projectId, ProjectPermission.AGENT_READ);
        AgentToolProposalEntity entity = require(proposalId);
        requireScope(entity, workspaceId, projectId, runId);
        return view(entity);
    }

    @Transactional(readOnly = true)
    public AgentToolProposalView getPendingForUser(long actor, long workspaceId, long projectId, String runId) {
        projectAuthorization.requirePermission(actor, workspaceId, projectId, ProjectPermission.AGENT_READ);
        AgentToolProposalEntity entity = mapper.findPendingByRun(runId)
                .orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_NOT_FOUND));
        requireScope(entity, workspaceId, projectId, runId);
        return view(entity);
    }

    /** 行锁 + CAS 裁决双击及 Approve/Reject 竞态；批准时重新 RBAC 以关闭 TOCTOU 窗口。 */
    @Transactional
    public AgentToolProposalDecisionResult decide(long actor, long workspaceId, long projectId,
                                                   String runId, String proposalId,
                                                   AgentToolProposalDecision decision) {
        AgentToolProposalEntity entity = mapper.lockById(proposalId)
                .orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_NOT_FOUND));
        requireScope(entity, workspaceId, projectId, runId);
        if (entity.getActorId() != actor) throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_ACTOR_MISMATCH);
        AgentToolProposalStatus current = AgentToolProposalStatus.valueOf(entity.getStatus());
        if (current.isResolved()) return new AgentToolProposalDecisionResult(view(entity), false);
        if (current != AgentToolProposalStatus.PENDING_APPROVAL) {
            throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_STATE_CONFLICT);
        }
        AgentRunExecutionContext run = contextQuery.findByRunIdForRuntime(runId)
                .orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_RUN_NOT_FOUND));
        if (run.status() != AgentRunStatus.WAITING_APPROVAL) {
            // 已取消/终止的 Run 不能再通过旧 Proposal 触发副作用。
            throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_STATE_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!entity.getExpiresAt().isAfter(now)) return expireLocked(entity, now);
        projectAuthorization.requirePermission(actor, workspaceId, projectId, ProjectPermission.AGENT_PROPOSE);
        if (decision == AgentToolProposalDecision.REJECT) {
            transition(entity, AgentToolProposalStatus.REJECTED, now);
            boolean resumeRequired = resumeJavaRun(entity);
            return new AgentToolProposalDecisionResult(view(require(proposalId)), resumeRequired);
        }
        transition(entity, AgentToolProposalStatus.EXECUTING, now);
        TaskCreateProposalPayload payload = TaskCreateProposalPayload.from(read(entity.getCanonicalArguments()));
        // TaskService 使用 Proposal 固化参数和原 actor，并在这里再次验证 TASK_CREATE/assignee/project。
        TaskResponse task = taskService.createTaskAs(actor, workspaceId, projectId, payload.toCommand());
        Map<String, Object> result = new TreeMap<>();
        result.put("created", true); result.put("displayKey", task.displayKey());
        result.put("resourceId", String.valueOf(task.id())); result.put("status", task.status().name());
        if (mapper.markExecuted(proposalId, write(result), String.valueOf(task.id()), now, entity.getVersion() + 1) != 1) {
            throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_STATE_CONFLICT);
        }
        boolean resumeRequired = resumeJavaRun(entity);
        return new AgentToolProposalDecisionResult(view(require(proposalId)), resumeRequired);
    }

    @Transactional
    public List<AgentToolProposalDecisionResult> expireDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AgentToolProposalDecisionResult> results = new ArrayList<>();
        for (String id : mapper.findExpired(now, properties.expirationBatchSize())) {
            AgentToolProposalEntity entity = mapper.lockById(id).orElse(null);
            if (entity != null && AgentToolProposalStatus.PENDING_APPROVAL.name().equals(entity.getStatus())
                    && !entity.getExpiresAt().isAfter(now)) results.add(expireLocked(entity, now));
        }
        return List.copyOf(results);
    }

    private AgentToolProposalDecisionResult expireLocked(AgentToolProposalEntity entity, LocalDateTime now) {
        transition(entity, AgentToolProposalStatus.EXPIRED, now);
        boolean resumeRequired = resumeJavaRun(entity);
        return new AgentToolProposalDecisionResult(view(require(entity.getProposalId())), resumeRequired);
    }

    private void transition(AgentToolProposalEntity entity, AgentToolProposalStatus target, LocalDateTime now) {
        if (mapper.transition(entity.getProposalId(), entity.getStatus(), target.name(), now, entity.getVersion()) != 1) {
            throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_STATE_CONFLICT);
        }
    }

    private boolean resumeJavaRun(AgentToolProposalEntity entity) {
        AgentRunExecutionContext context = contextQuery.findByRunIdForRuntime(entity.getRunId()).orElseThrow();
        if (context.status() == AgentRunStatus.WAITING_APPROVAL) {
            runPersistence.markRunningAfterApproval(entity.getRunId(), context.version());
            return true;
        }
        return false;
    }

    private AgentRunExecutionContext requireContext(String runId, String requestId, AgentRunStatus status) {
        AgentRunExecutionContext context = requireContextAny(runId, requestId);
        if (context.status() != status) throw new AgentToolException(AgentToolErrorKind.RUN_NOT_ACTIVE);
        return context;
    }

    private AgentRunExecutionContext requireContextAny(String runId, String requestId) {
        AgentRunExecutionContext context = contextQuery.findByRunIdForRuntime(runId)
                .orElseThrow(() -> new AgentToolException(AgentToolErrorKind.RUN_NOT_FOUND));
        if (!context.requestId().equals(requestId)) throw new AgentToolException(AgentToolErrorKind.PROTOCOL);
        return context;
    }

    private void requireScope(AgentToolProposalEntity p, long workspaceId, long projectId, String runId) {
        if (p.getWorkspaceId() != workspaceId || p.getProjectId() != projectId || !p.getRunId().equals(runId))
            throw new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_NOT_FOUND);
    }

    private AgentToolProposalEntity require(String id) {
        return mapper.findById(id).orElseThrow(() -> new BusinessException(AgentRunErrorCode.AGENT_PROPOSAL_NOT_FOUND));
    }

    private Map<String, Object> canonical(TaskCreateProposalPayload p) {
        Map<String, Object> value = new TreeMap<>(); value.put("title", p.title());
        if (p.description() != null) value.put("description", p.description());
        value.put("priority", p.priority().name());
        if (p.assigneeUserId() != null) value.put("assigneeUserId", p.assigneeUserId());
        if (p.dueAt() != null) value.put("dueAt", p.dueAt().toString());
        return value;
    }

    private AgentToolProposalView view(AgentToolProposalEntity p) {
        return new AgentToolProposalView(p.getProposalId(), p.getRunId(), p.getActorId(), p.getWorkspaceId(),
                p.getProjectId(), p.getToolCallId(), p.getToolName(), read(p.getCanonicalArguments()),
                p.getPayloadHash(), p.getIdempotencyKey(), AgentToolProposalStatus.valueOf(p.getStatus()),
                p.getCreatedAt(), p.getExpiresAt(), p.getDecisionAt(), p.getExecutedAt(),
                p.getExecutionResult() == null ? Map.of() : read(p.getExecutionResult()),
                p.getResourceId(), p.getFailureReason(), p.getVersion());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new AgentToolException(AgentToolErrorKind.INTERNAL); }
    }
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, MAP); }
        catch (JsonProcessingException e) { throw new AgentToolException(AgentToolErrorKind.INTERNAL); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void requireIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.matches("[A-Za-z0-9_.:-]+"))
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
    }
}
