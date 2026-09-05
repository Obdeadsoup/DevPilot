package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步流的业务仲裁点：校验协议、持有可取消句柄，并用 RUNNING/version 条件更新裁决唯一终态。
 * gRPC callback 与 HTTP Cancel 可能并发，数据库更新结果决定唯一对外事实。
 */
@Service
public class AgentRunStreamCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentRunStreamCoordinator.class);
    private static final Set<String> REMOTE_FAILURE_KINDS = Set.of(
            "MAX_STEPS", "MODEL_ERROR", "TOOL_ERROR", "INVALID_TOOL_CALL", "MAX_TOOL_CALLS", "INTERNAL");

    private final AgentRuntimeStreamingPort streamingPort;
    private final AgentRuntimeCancellationPort cancellationPort;
    private final AgentRunPersistenceService persistenceService;
    private final AgentRunTimeProvider timeProvider;
    private final AgentRunEventPublisher eventPublisher;
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> publishedSequences = new ConcurrentHashMap<>();

    public AgentRunStreamCoordinator(AgentRuntimeStreamingPort streamingPort,
                                     AgentRuntimeCancellationPort cancellationPort,
                                     AgentRunPersistenceService persistenceService,
                                     AgentRunTimeProvider timeProvider,
                                     AgentRunEventPublisher eventPublisher) {
        this.streamingPort = streamingPort;
        this.cancellationPort = cancellationPort;
        this.persistenceService = persistenceService;
        this.timeProvider = timeProvider;
        this.eventPublisher = eventPublisher;
    }

    /** RUNNING 已由调用方提交；本方法只启动 async RPC，不等待 terminal。 */
    public void start(long workspaceId, long projectId, AgentRunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        publishedSequences.put(command.runId(), new AtomicLong());
        eventPublisher.initialize(command.runId());
        RunListener listener = new RunListener(workspaceId, projectId, command.runId(), false);
        ActiveRun active = new ActiveRun(listener);
        activeRuns.put(command.runId(), active);
        try {
            AgentRuntimeStreamHandle handle = streamingPort.stream(command, listener);
            active.handle = handle;
            if (activeRuns.get(command.runId()) != active) {
                // callback/cancel 可能在 stream(...) 返回句柄前完成；补取消避免孤儿流。
                handle.cancel();
            }
        } catch (RuntimeException exception) {
            listener.onError(AgentRuntimeStreamFailureKind.UNKNOWN);
        }
    }

    /** Proposal 事务已提交且 Java Run 已回到 RUNNING 后，启动专用 Runtime 恢复流。 */
    public void resumeApproval(long workspaceId, long projectId, AgentRunView run, String proposalId) {
        RunListener listener = new RunListener(workspaceId, projectId, run.runId(), true);
        ActiveRun active = new ActiveRun(listener);
        if (activeRuns.putIfAbsent(run.runId(), active) != null) {
            throw new BusinessException(com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode.AGENT_RUN_STATE_CONFLICT);
        }
        try {
            AgentRuntimeStreamHandle handle = streamingPort.resumeApproval(
                    new AgentApprovalResumeCommand(run.requestId(), run.runId(), proposalId), listener);
            active.handle = handle;
            if (activeRuns.get(run.runId()) != active) handle.cancel();
        } catch (RuntimeException exception) {
            listener.onError(AgentRuntimeStreamFailureKind.UNKNOWN);
        }
    }

    /** Python 接受取消后才竞争本地 CANCELLED；若远端 terminal 已先落库，则返回该权威终态。 */
    public AgentRunView cancel(long workspaceId, long projectId, AgentRunView current) {
        AgentRuntimeCancelStatus status = cancellationPort.cancel(
                new AgentRuntimeCancelCommand(current.runId(), current.requestId()));
        if (status != AgentRuntimeCancelStatus.ACCEPTED) {
            throw new AgentRuntimeCancellationException();
        }
        ActiveRun active = activeRuns.get(current.runId());
        if (active != null) {
            return active.listener.cancelAccepted();
        }
        return cancelWithoutLocalStream(workspaceId, projectId, current.runId());
    }

    private AgentRunView cancelWithoutLocalStream(long workspaceId, long projectId, String runId) {
        return persistenceService.tryMarkCancelled(workspaceId, projectId, runId, timeProvider.now())
                .map(view -> {
                    publish(new AgentStreamEvent(runId + ":1", runId, 1,
                            AgentStreamEventType.RUN_CANCELLED, 0, "", "", ""));
                    publishedSequences.remove(runId);
                    return view;
                })
                .orElseGet(() -> persistenceService.get(workspaceId, projectId, runId));
    }

    private AgentRunFailureKind remoteFailure(String failureKind) {
        return switch (failureKind) {
            case "MODEL_ERROR" -> AgentRunFailureKind.MODEL_ERROR;
            case "TOOL_ERROR" -> AgentRunFailureKind.TOOL_ERROR;
            case "MAX_STEPS" -> AgentRunFailureKind.MAX_STEPS;
            case "INTERNAL" -> AgentRunFailureKind.INTERNAL;
            default -> AgentRunFailureKind.PROTOCOL;
        };
    }

    private final class RunListener implements AgentRuntimeEventListener {
        private final long workspaceId;
        private final long projectId;
        private final String runId;
        private long expectedSequence = 1;
        private boolean terminalSeen;
        private boolean streamFinished;
        private final boolean resumed;

        private RunListener(long workspaceId, long projectId, String runId, boolean resumed) {
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.runId = runId;
            this.resumed = resumed;
        }

        @Override
        public synchronized void onEvent(AgentStreamEvent event) {
            if (streamFinished) {
                return;
            }
            if (terminalSeen) {
                protocolAfterTerminal("event after terminal");
                return;
            }
            if (!validEnvelope(event) || !validPayload(event)) {
                failBeforeTerminal(AgentRuntimeStreamFailureKind.PROTOCOL);
                return;
            }
            boolean publish = true;
            if (event.type() == AgentStreamEventType.RUN_SUCCEEDED) {
                publish = persistenceService.tryMarkSucceeded(workspaceId, projectId, runId,
                        event.finalOutput(), timeProvider.now()).isPresent();
                terminalSeen = true;
            } else if (event.type() == AgentStreamEventType.RUN_FAILED) {
                publish = persistenceService.tryMarkFailed(workspaceId, projectId, runId,
                        remoteFailure(event.failureKind()), timeProvider.now()).isPresent();
                terminalSeen = true;
            } else if (event.type() == AgentStreamEventType.RUN_CANCELLED) {
                publish = persistenceService.tryMarkCancelled(
                        workspaceId, projectId, runId, timeProvider.now()).isPresent();
                terminalSeen = true;
            } else if (event.type() == AgentStreamEventType.RUN_WAITING_APPROVAL) {
                terminalSeen = true;
            }
            if (publish) {
                AgentRunStreamCoordinator.this.publish(event);
            }
            expectedSequence++;
            if (event.type().isTerminal()) {
                streamFinished = true;
                activeRuns.remove(runId);
                if (event.type().isRunTerminal()) publishedSequences.remove(runId);
            }
        }

        @Override
        public synchronized void onError(AgentRuntimeStreamFailureKind failureKind) {
            Objects.requireNonNull(failureKind, "failureKind must not be null");
            if (streamFinished) {
                return;
            }
            if (terminalSeen || failureKind == AgentRuntimeStreamFailureKind.USER_CANCELLED) {
                streamFinished = true;
                activeRuns.remove(runId);
                return;
            }
            failBeforeTerminal(failureKind);
        }

        @Override
        public synchronized void onCompleted() {
            if (streamFinished) {
                return;
            }
            if (!terminalSeen) {
                failBeforeTerminal(AgentRuntimeStreamFailureKind.PROTOCOL);
                return;
            }
            streamFinished = true;
            activeRuns.remove(runId);
        }

        private synchronized AgentRunView cancelAccepted() {
            AgentRunView result = persistenceService.tryMarkCancelled(
                            workspaceId, projectId, runId, timeProvider.now())
                    .map(view -> {
                        AgentStreamEvent event = new AgentStreamEvent(runId + ":" + expectedSequence,
                                runId, expectedSequence, AgentStreamEventType.RUN_CANCELLED,
                                0, "", "", "");
                        terminalSeen = true;
                        streamFinished = true;
                        AgentRunStreamCoordinator.this.publish(event);
                        publishedSequences.remove(runId);
                        expectedSequence++;
                        return view;
                    })
                    .orElseGet(() -> persistenceService.get(workspaceId, projectId, runId));
            ActiveRun active = activeRuns.remove(runId);
            if (active != null) {
                active.handle.cancel();
            }
            return result;
        }

        private boolean validEnvelope(AgentStreamEvent event) {
            return runId.equals(event.runId())
                    && event.sequence() == expectedSequence
                    && event.sequence() > 0
                    && (runId + ":" + event.sequence()).equals(event.eventId())
                    && (expectedSequence != 1 || event.type() ==
                        (resumed ? AgentStreamEventType.RUN_RESUMED : AgentStreamEventType.RUN_STARTED))
                    && (expectedSequence == 1 || (event.type() != AgentStreamEventType.RUN_STARTED
                        && event.type() != AgentStreamEventType.RUN_RESUMED));
        }

        private boolean validPayload(AgentStreamEvent event) {
            boolean noTool = blank(event.toolName());
            boolean noOutput = blank(event.finalOutput());
            boolean noFailure = blank(event.failureKind());
            return switch (event.type()) {
                case RUN_STARTED -> event.step() == 0 && noTool && noOutput && noFailure;
                case MODEL_STEP_STARTED -> event.step() > 0 && noTool && noOutput && noFailure;
                case TOOL_STARTED, TOOL_COMPLETED -> event.step() > 0
                        && !noTool && event.toolName().matches("[A-Za-z0-9_.-]{1,128}")
                        && noOutput && noFailure;
                case RUN_SUCCEEDED -> event.step() == 0 && noTool && noFailure
                        && event.finalOutput() != null && event.finalOutput().length() <= 65_535;
                case RUN_FAILED -> event.step() == 0 && noTool && noOutput
                        && REMOTE_FAILURE_KINDS.contains(event.failureKind());
                case RUN_CANCELLED -> event.step() == 0 && noTool && noOutput && noFailure;
                case RUN_RESUMED -> event.step() == 0 && noTool && noOutput && noFailure;
                case RUN_WAITING_APPROVAL -> event.step() == 0 && noTool && noOutput && noFailure
                        && event.proposalId() != null
                        && event.proposalId().matches("[A-Za-z0-9-]{1,64}")
                        && event.proposalExpiresAt() != null && !event.proposalExpiresAt().isBlank();
            };
        }

        private void failBeforeTerminal(AgentRuntimeStreamFailureKind failureKind) {
            AgentRunFailureKind persisted = AgentRunFailureKind.valueOf(failureKind.name());
            boolean won = persistenceService.tryMarkFailed(
                    workspaceId, projectId, runId, persisted, timeProvider.now()).isPresent();
            if (won) {
                AgentRunStreamCoordinator.this.publish(new AgentStreamEvent(runId + ":" + expectedSequence,
                        runId, expectedSequence, AgentStreamEventType.RUN_FAILED,
                        0, "", "", failureKind.name()));
            }
            terminalSeen = true;
            streamFinished = true;
            activeRuns.remove(runId);
            publishedSequences.remove(runId);
            expectedSequence++;
        }

        private void protocolAfterTerminal(String reason) {
            log.warn("Agent stream protocol violation runId={} kind={}", runId, reason);
            streamFinished = true;
            activeRuns.remove(runId);
        }

        private boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /** 每次 Runtime resume 都从 1 计数；这里转换为单个 Run 的连续 SSE 序号。 */
    private void publish(AgentStreamEvent event) {
        long sequence = publishedSequences.computeIfAbsent(event.runId(), ignored -> new AtomicLong())
                .incrementAndGet();
        eventPublisher.publish(new AgentStreamEvent(event.runId() + ":" + sequence, event.runId(), sequence,
                event.type(), event.step(), event.toolName(), event.finalOutput(), event.failureKind(),
                event.proposalId(), event.proposalExpiresAt()));
    }

    private static final class ActiveRun {
        private final RunListener listener;
        private volatile AgentRuntimeStreamHandle handle = AgentRuntimeStreamHandle.NOOP;

        private ActiveRun(RunListener listener) {
            this.listener = listener;
        }
    }
}
