package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamingPort;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEvent;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.StreamRunRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** async Stub Adapter：立即发起 Server Streaming，并把 StreamObserver callback 映射到 Core listener。 */
public final class GrpcAgentRuntimeStreamingClient implements AgentRuntimeStreamingPort {
    private final AgentRuntimeGrpc.AgentRuntimeStub stub;
    private final Duration deadline;

    public GrpcAgentRuntimeStreamingClient(AgentRuntimeGrpc.AgentRuntimeStub stub, Duration deadline) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        this.deadline = Objects.requireNonNull(deadline, "deadline must not be null");
    }

    @Override
    public void stream(AgentRunCommand command, AgentRuntimeEventListener listener) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        StreamRunRequest request = StreamRunRequest.newBuilder()
                .setRunId(command.runId())
                .setRequestId(command.requestId())
                .setUserInput(command.userInput())
                .build();
        AtomicBoolean adapterFailed = new AtomicBoolean();
        try {
            stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .streamRun(request, new StreamObserver<>() {
                    @Override
                    public void onNext(AgentEvent value) {
                        if (adapterFailed.get()) {
                            return;
                        }
                        try {
                            listener.onEvent(toInternal(value));
                        } catch (RuntimeException exception) {
                            // generated DTO/回调异常不能逃逸到 gRPC executor；统一收敛为协议失败一次。
                            if (adapterFailed.compareAndSet(false, true)) {
                                listener.onError(AgentRuntimeStreamFailureKind.PROTOCOL);
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (adapterFailed.compareAndSet(false, true)) {
                            listener.onError(mapStatus(Status.fromThrowable(error).getCode()));
                        }
                    }

                    @Override
                    public void onCompleted() {
                        if (!adapterFailed.get()) {
                            listener.onCompleted();
                        }
                    }
                    });
        } catch (RuntimeException exception) {
            if (adapterFailed.compareAndSet(false, true)) {
                listener.onError(mapStatus(Status.fromThrowable(exception).getCode()));
            }
        }
    }

    private AgentStreamEvent toInternal(AgentEvent event) {
        AgentStreamEventType type = switch (event.getType()) {
            case AGENT_EVENT_TYPE_RUN_STARTED -> AgentStreamEventType.RUN_STARTED;
            case AGENT_EVENT_TYPE_MODEL_STEP_STARTED -> AgentStreamEventType.MODEL_STEP_STARTED;
            case AGENT_EVENT_TYPE_TOOL_STARTED -> AgentStreamEventType.TOOL_STARTED;
            case AGENT_EVENT_TYPE_TOOL_COMPLETED -> AgentStreamEventType.TOOL_COMPLETED;
            case AGENT_EVENT_TYPE_RUN_SUCCEEDED -> AgentStreamEventType.RUN_SUCCEEDED;
            case AGENT_EVENT_TYPE_RUN_FAILED -> AgentStreamEventType.RUN_FAILED;
            case AGENT_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("unsupported AgentEvent type");
        };
        return new AgentStreamEvent(
                event.getEventId(), event.getRunId(), event.getSequence(), type, event.getStep(),
                event.getToolName(), event.getFinalOutput(), event.getFailureKind());
    }

    private AgentRuntimeStreamFailureKind mapStatus(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED;
            case UNAVAILABLE -> AgentRuntimeStreamFailureKind.UNAVAILABLE;
            case INVALID_ARGUMENT -> AgentRuntimeStreamFailureKind.INVALID_ARGUMENT;
            case INTERNAL -> AgentRuntimeStreamFailureKind.INTERNAL;
            case UNKNOWN -> AgentRuntimeStreamFailureKind.UNKNOWN;
            default -> AgentRuntimeStreamFailureKind.UNKNOWN;
        };
    }
}
