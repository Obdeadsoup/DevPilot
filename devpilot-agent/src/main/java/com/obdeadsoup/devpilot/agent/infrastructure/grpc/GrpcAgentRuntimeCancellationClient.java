package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancellationException;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancellationPort;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Java→Python CancelRun Adapter；稳定映射协议状态并隐藏 gRPC description。 */
public final class GrpcAgentRuntimeCancellationClient implements AgentRuntimeCancellationPort {
    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub stub;
    private final Duration deadline;

    public GrpcAgentRuntimeCancellationClient(AgentRuntimeGrpc.AgentRuntimeBlockingStub stub, Duration deadline) {
        this.stub = Objects.requireNonNull(stub, "stub must not be null");
        this.deadline = Objects.requireNonNull(deadline, "deadline must not be null");
    }

    @Override
    public AgentRuntimeCancelStatus cancel(AgentRuntimeCancelCommand command) {
        try {
            CancelRunResponse response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .cancelRun(CancelRunRequest.newBuilder()
                            .setRunId(command.runId())
                            .setRequestId(command.requestId())
                            .build());
            return switch (response.getStatus()) {
                case CANCEL_RUN_STATUS_ACCEPTED -> AgentRuntimeCancelStatus.ACCEPTED;
                case CANCEL_RUN_STATUS_NOT_FOUND -> AgentRuntimeCancelStatus.NOT_FOUND;
                case CANCEL_RUN_STATUS_ALREADY_TERMINAL -> AgentRuntimeCancelStatus.ALREADY_TERMINAL;
                case CANCEL_RUN_STATUS_UNSPECIFIED, UNRECOGNIZED -> throw new AgentRuntimeCancellationException();
            };
        } catch (AgentRuntimeCancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentRuntimeCancellationException();
        }
    }
}
