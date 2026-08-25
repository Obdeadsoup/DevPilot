package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRunResult;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimePort;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.RunStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AgentRuntimePort 的 gRPC Adapter：负责内部模型与 protobuf 双向映射及稳定 Status 分类。
 *
 * <p>每次调用显式设置 Deadline 且不自动 Retry。Deadline 超时只表示 Java 停止等待，不能证明 Python 未执行。</p>
 */
public final class GrpcAgentRuntimeClient implements AgentRuntimePort {

    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub stub;
    private final Duration deadline;

    public GrpcAgentRuntimeClient(
            AgentRuntimeGrpc.AgentRuntimeBlockingStub stub,
            Duration deadline
    ) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        this.deadline = Objects.requireNonNull(deadline, "deadline must not be null");
    }

    @Override
    public AgentRunResult run(AgentRunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        StartRunRequest request = StartRunRequest.newBuilder()
                .setRequestId(command.requestId())
                .setRunId(command.runId())
                .setUserInput(command.userInput())
                .build();
        try {
            StartRunResponse response = stub
                    .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .startRun(request);
            return toInternalResult(command, response);
        } catch (StatusRuntimeException error) {
            throw new AgentRuntimeClientException(mapStatus(error.getStatus().getCode()), error);
        }
    }

    private AgentRunResult toInternalResult(
            AgentRunCommand command,
            StartRunResponse response
    ) {
        if (response.getRunId().isBlank() || !response.getRunId().equals(command.runId())) {
            throw new AgentRuntimeClientException(AgentRuntimeFailureKind.PROTOCOL);
        }
        AgentRunStatus status = switch (response.getStatus()) {
            case RUN_STATUS_SUCCEEDED -> AgentRunStatus.SUCCEEDED;
            case RUN_STATUS_FAILED -> AgentRunStatus.FAILED;
            case RUN_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new AgentRuntimeClientException(AgentRuntimeFailureKind.PROTOCOL);
        };
        return new AgentRunResult(response.getRunId(), response.getFinalOutput(), status);
    }

    private AgentRuntimeFailureKind mapStatus(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> AgentRuntimeFailureKind.DEADLINE_EXCEEDED;
            case UNAVAILABLE -> AgentRuntimeFailureKind.UNAVAILABLE;
            case INVALID_ARGUMENT -> AgentRuntimeFailureKind.INVALID_ARGUMENT;
            case INTERNAL -> AgentRuntimeFailureKind.INTERNAL;
            case UNKNOWN -> AgentRuntimeFailureKind.UNKNOWN;
            default -> AgentRuntimeFailureKind.UNKNOWN;
        };
    }
}
