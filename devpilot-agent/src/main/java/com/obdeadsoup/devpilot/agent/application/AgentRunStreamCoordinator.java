package com.obdeadsoup.devpilot.agent.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * 异步流的业务仲裁点：校验 run/sequence/eventId/唯一终态，先投影数据库终态，再发布 SSE。
 * gRPC callback 不处于 HTTP 线程或创建 RUNNING 的事务中。
 */
@Service
public class AgentRunStreamCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentRunStreamCoordinator.class);
    private static final Set<String> REMOTE_FAILURE_KINDS = Set.of(
            "MAX_STEPS", "MODEL_ERROR", "TOOL_ERROR", "INVALID_TOOL_CALL",
            "MAX_TOOL_CALLS", "INTERNAL");

    private final AgentRuntimeStreamingPort streamingPort;
    private final AgentRunPersistenceService persistenceService;
    private final AgentRunTimeProvider timeProvider;
    private final AgentRunEventPublisher eventPublisher;

    public AgentRunStreamCoordinator(AgentRuntimeStreamingPort streamingPort,
                                     AgentRunPersistenceService persistenceService,
                                     AgentRunTimeProvider timeProvider,
                                     AgentRunEventPublisher eventPublisher) {
        this.streamingPort = streamingPort;
        this.persistenceService = persistenceService;
        this.timeProvider = timeProvider;
        this.eventPublisher = eventPublisher;
    }

    /** RUNNING 已由调用方提交；本方法只启动 async RPC，不等待 terminal。 */
    public void start(long workspaceId, long projectId, AgentRunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        eventPublisher.initialize(command.runId());
        RunListener listener = new RunListener(workspaceId, projectId, command.runId());
        try {
            streamingPort.stream(command, listener);
        } catch (RuntimeException exception) {
            // 极少数同步建流失败也必须把已提交 RUNNING 收敛为稳定失败。
            listener.onError(AgentRuntimeStreamFailureKind.UNKNOWN);
        }
    }

    private final class RunListener implements AgentRuntimeEventListener {
        private final long workspaceId;
        private final long projectId;
        private final String runId;
        private long expectedSequence = 1;
        private boolean terminalSeen;
        private boolean streamFinished;

        private RunListener(long workspaceId, long projectId, String runId) {
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.runId = runId;
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
            if (event.type() == AgentStreamEventType.RUN_SUCCEEDED) {
                persistenceService.markSucceeded(workspaceId, projectId, runId,
                        event.finalOutput(), timeProvider.now());
                terminalSeen = true;
            } else if (event.type() == AgentStreamEventType.RUN_FAILED) {
                persistenceService.markFailed(workspaceId, projectId, runId,
                        AgentRunFailureKind.REMOTE_FAILED, timeProvider.now());
                terminalSeen = true;
            }
            eventPublisher.publish(event);
            expectedSequence++;
        }

        @Override
        public synchronized void onError(AgentRuntimeStreamFailureKind failureKind) {
            Objects.requireNonNull(failureKind, "failureKind must not be null");
            if (streamFinished) {
                return;
            }
            if (terminalSeen) {
                // terminal 已经以 version=0 条件提交；后到 transport error 不能覆盖业务事实。
                streamFinished = true;
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
        }

        private boolean validEnvelope(AgentStreamEvent event) {
            return runId.equals(event.runId())
                    && event.sequence() == expectedSequence
                    && event.sequence() > 0
                    && (runId + ":" + event.sequence()).equals(event.eventId())
                    && (expectedSequence != 1 || event.type() == AgentStreamEventType.RUN_STARTED)
                    && (expectedSequence == 1 || event.type() != AgentStreamEventType.RUN_STARTED);
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
            };
        }

        private void failBeforeTerminal(AgentRuntimeStreamFailureKind failureKind) {
            AgentRunFailureKind persisted = AgentRunFailureKind.valueOf(failureKind.name());
            persistenceService.markFailed(workspaceId, projectId, runId, persisted, timeProvider.now());
            AgentStreamEvent synthetic = new AgentStreamEvent(
                    runId + ":" + expectedSequence,
                    runId,
                    expectedSequence,
                    AgentStreamEventType.RUN_FAILED,
                    0,
                    "",
                    "",
                    failureKind.name());
            terminalSeen = true;
            streamFinished = true;
            eventPublisher.publish(synthetic);
            expectedSequence++;
        }

        private void protocolAfterTerminal(String reason) {
            // 数据库终态不可回退；只记录低敏协议类别，绝不写远端 payload。
            log.warn("Agent stream protocol violation runId={} kind={}", runId, reason);
            streamFinished = true;
        }

        private boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
