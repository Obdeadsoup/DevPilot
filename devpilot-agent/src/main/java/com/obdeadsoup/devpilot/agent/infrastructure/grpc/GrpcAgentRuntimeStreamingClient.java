package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.*;
import com.obdeadsoup.devpilot.agent.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** async Stub Adapter：普通执行和审批恢复共用同一受校验的事件映射。 */
public final class GrpcAgentRuntimeStreamingClient implements AgentRuntimeStreamingPort {
    private final AgentRuntimeGrpc.AgentRuntimeStub stub;
    private final Duration deadline;

    public GrpcAgentRuntimeStreamingClient(AgentRuntimeGrpc.AgentRuntimeStub stub, Duration deadline) {
        this.stub = Objects.requireNonNull(stub); this.deadline = Objects.requireNonNull(deadline);
    }

    @Override
    public AgentRuntimeStreamHandle stream(AgentRunCommand command, AgentRuntimeEventListener listener) {
        StreamRunRequest request = StreamRunRequest.newBuilder().setRunId(command.runId())
                .setRequestId(command.requestId()).setUserInput(command.userInput()).build();
        return start(request, (value, observer) -> stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .streamRun(value, observer), listener);
    }

    @Override
    public AgentRuntimeStreamHandle resumeApproval(AgentApprovalResumeCommand command,
                                                   AgentRuntimeEventListener listener) {
        ResumeApprovalRequest request = ResumeApprovalRequest.newBuilder().setRunId(command.runId())
                .setRequestId(command.requestId()).setProposalId(command.proposalId()).build();
        return start(request, (value, observer) -> stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .resumeApproval(value, observer), listener);
    }

    private <T> AgentRuntimeStreamHandle start(T request, Starter<T> starter,
                                               AgentRuntimeEventListener listener) {
        Objects.requireNonNull(listener);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<ClientCallStreamObserver<T>> call = new AtomicReference<>();
        ClientResponseObserver<T, AgentEvent> observer = new ClientResponseObserver<>() {
            @Override public void beforeStart(ClientCallStreamObserver<T> stream) { call.set(stream); }
            @Override public void onNext(AgentEvent value) {
                if (finished.get()) return;
                try { listener.onEvent(toInternal(value)); }
                catch (RuntimeException exception) {
                    if (finished.compareAndSet(false, true))
                        listener.onError(AgentRuntimeStreamFailureKind.PROTOCOL);
                }
            }
            @Override public void onError(Throwable error) {
                if (finished.compareAndSet(false, true))
                    listener.onError(mapStatus(Status.fromThrowable(error).getCode()));
            }
            @Override public void onCompleted() { if (!finished.get()) listener.onCompleted(); }
        };
        try { starter.start(request, observer); }
        catch (RuntimeException exception) {
            if (finished.compareAndSet(false, true))
                listener.onError(mapStatus(Status.fromThrowable(exception).getCode()));
        }
        return () -> {
            ClientCallStreamObserver<T> active = call.get();
            if (active != null && finished.compareAndSet(false, true))
                active.cancel("cancelled by DevPilot Core", null);
        };
    }

    private AgentStreamEvent toInternal(AgentEvent event) {
        AgentStreamEventType type = switch (event.getType()) {
            case AGENT_EVENT_TYPE_RUN_STARTED -> AgentStreamEventType.RUN_STARTED;
            case AGENT_EVENT_TYPE_MODEL_STEP_STARTED -> AgentStreamEventType.MODEL_STEP_STARTED;
            case AGENT_EVENT_TYPE_TOOL_STARTED -> AgentStreamEventType.TOOL_STARTED;
            case AGENT_EVENT_TYPE_TOOL_COMPLETED -> AgentStreamEventType.TOOL_COMPLETED;
            case AGENT_EVENT_TYPE_RUN_SUCCEEDED -> AgentStreamEventType.RUN_SUCCEEDED;
            case AGENT_EVENT_TYPE_RUN_FAILED -> AgentStreamEventType.RUN_FAILED;
            case AGENT_EVENT_TYPE_RUN_CANCELLED -> AgentStreamEventType.RUN_CANCELLED;
            case AGENT_EVENT_TYPE_RUN_WAITING_APPROVAL -> AgentStreamEventType.RUN_WAITING_APPROVAL;
            case AGENT_EVENT_TYPE_RUN_RESUMED -> AgentStreamEventType.RUN_RESUMED;
            case AGENT_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException();
        };
        return new AgentStreamEvent(event.getEventId(), event.getRunId(), event.getSequence(), type,
                event.getStep(), event.getToolName(), event.getFinalOutput(), event.getFailureKind(),
                event.getProposalId(), event.getProposalExpiresAt());
    }

    private AgentRuntimeStreamFailureKind mapStatus(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED;
            case UNAVAILABLE -> AgentRuntimeStreamFailureKind.UNAVAILABLE;
            case INVALID_ARGUMENT -> AgentRuntimeStreamFailureKind.INVALID_ARGUMENT;
            case INTERNAL -> AgentRuntimeStreamFailureKind.INTERNAL;
            case CANCELLED -> AgentRuntimeStreamFailureKind.USER_CANCELLED;
            default -> AgentRuntimeStreamFailureKind.UNKNOWN;
        };
    }

    @FunctionalInterface
    private interface Starter<T> {
        void start(T request, ClientResponseObserver<T, AgentEvent> observer);
    }
}
