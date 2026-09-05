package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.google.protobuf.Struct;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolApplicationService;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolCommand;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolErrorKind;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolException;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolResult;
import com.obdeadsoup.devpilot.agent.application.proposal.*;
import com.obdeadsoup.devpilot.agent.config.AgentToolGrpcProperties;
import com.obdeadsoup.devpilot.agent.contract.v1.DevPilotToolGatewayGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.ExecuteToolRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.ExecuteToolResponse;
import com.obdeadsoup.devpilot.agent.contract.v1.ToolExecutionStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.*;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** protobuf 入站 Adapter：只做 wire 校验/映射，业务委托与 allowlist 位于 Application Service。 */
@Component
public final class DevPilotToolGatewayGrpcService
        extends DevPilotToolGatewayGrpc.DevPilotToolGatewayImplBase {
    private final AgentToolApplicationService applicationService;
    private final AgentToolGrpcProperties properties;
    private final AgentToolGatewayMetrics metrics;
    private final AgentToolProposalService proposalService;
    private final ProtoStructMapper structMapper = new ProtoStructMapper();

    @Autowired
    public DevPilotToolGatewayGrpcService(AgentToolApplicationService applicationService,
                                          AgentToolGrpcProperties properties,
                                          AgentToolGatewayMetrics metrics,
                                          AgentToolProposalService proposalService) {
        this.applicationService = applicationService;
        this.properties = properties;
        this.metrics = metrics;
        this.proposalService = proposalService;
    }

    public DevPilotToolGatewayGrpcService(AgentToolApplicationService applicationService,
                                          AgentToolGrpcProperties properties,
                                          AgentToolGatewayMetrics metrics) {
        this(applicationService, properties, metrics, null);
    }

    @Override
    public void createToolProposal(CreateToolProposalRequest request,
                                   StreamObserver<CreateToolProposalResponse> observer) {
        long started = System.nanoTime();
        try {
            if (!request.hasArguments()) throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
            AgentToolProposalView proposal = proposalService.create(new CreateAgentToolProposalCommand(
                    request.getRequestId(), request.getRunId(), request.getToolCallId(),
                    request.getToolName(), structMapper.fromProto(request.getArguments())));
            observer.onNext(CreateToolProposalResponse.newBuilder().setProposalId(proposal.proposalId())
                    .setToolCallId(proposal.toolCallId()).setStatus(proposal.status().name())
                    .setExpiresAt(proposal.expiresAt().toString()).build());
            observer.onCompleted(); metrics.record(request.getToolName(), true, null, started);
        } catch (AgentToolException exception) {
            failAny(observer, request.getToolName(), exception.kind(), started);
        } catch (BusinessException exception) {
            failAny(observer, request.getToolName(), businessKind(exception), started);
        } catch (RuntimeException exception) {
            failAny(observer, request.getToolName(), AgentToolErrorKind.INTERNAL, started);
        }
    }

    @Override
    public void getToolProposal(GetToolProposalRequest request,
                                StreamObserver<GetToolProposalResponse> observer) {
        long started = System.nanoTime();
        try {
            AgentToolProposalView proposal = proposalService.getForRuntime(
                    request.getRunId(), request.getRequestId(), request.getProposalId());
            GetToolProposalResponse response = GetToolProposalResponse.newBuilder()
                    .setProposalId(proposal.proposalId()).setToolCallId(proposal.toolCallId())
                    .setToolName(proposal.toolName()).setStatus(toProto(proposal.status()))
                    .setResult(structMapper.toProto(proposal.executionResult()))
                    .setExpiresAt(proposal.expiresAt().toString()).build();
            observer.onNext(response); observer.onCompleted();
            metrics.record(proposal.toolName(), true, null, started);
        } catch (AgentToolException exception) {
            failAny(observer, "proposal.get", exception.kind(), started);
        } catch (BusinessException exception) {
            failAny(observer, "proposal.get", businessKind(exception), started);
        } catch (RuntimeException exception) {
            failAny(observer, "proposal.get", AgentToolErrorKind.INTERNAL, started);
        }
    }

    @Override
    public void executeTool(ExecuteToolRequest request,
                            StreamObserver<ExecuteToolResponse> responseObserver) {
        long started = System.nanoTime();
        String toolName = request.getToolName();
        try {
            if (!request.hasArguments()) {
                throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
            }
            AgentToolResult result = applicationService.execute(new AgentToolCommand(
                    request.getRequestId(), request.getRunId(), request.getToolCallId(), toolName,
                    structMapper.fromProto(request.getArguments())));
            Struct resultStruct = structMapper.toProto(result.data());
            if (resultStruct.getSerializedSize() > properties.maxResultBytes()) {
                throw new AgentToolException(AgentToolErrorKind.RESULT_TOO_LARGE);
            }
            responseObserver.onNext(ExecuteToolResponse.newBuilder()
                    .setResultId(result.resultId())
                    .setToolCallId(result.toolCallId())
                    .setStatus(ToolExecutionStatus.TOOL_EXECUTION_STATUS_SUCCEEDED)
                    .setResult(resultStruct)
                    .build());
            responseObserver.onCompleted();
            metrics.record(toolName, true, null, started);
        } catch (AgentToolException exception) {
            fail(responseObserver, toolName, exception.kind(), started);
        } catch (BusinessException exception) {
            AgentToolErrorKind kind = switch (exception.errorCode().status().value()) {
                case 400 -> AgentToolErrorKind.INVALID_ARGUMENT;
                case 403 -> AgentToolErrorKind.PERMISSION_DENIED;
                case 404 -> AgentToolErrorKind.NOT_FOUND;
                default -> AgentToolErrorKind.INTERNAL;
            };
            fail(responseObserver, toolName, kind, started);
        } catch (IllegalArgumentException exception) {
            fail(responseObserver, toolName, AgentToolErrorKind.INVALID_ARGUMENT, started);
        } catch (RuntimeException exception) {
            fail(responseObserver, toolName, AgentToolErrorKind.INTERNAL, started);
        }
    }

    private void fail(StreamObserver<ExecuteToolResponse> observer, String toolName,
                      AgentToolErrorKind kind, long started) {
        metrics.record(toolName, false, kind, started);
        Status status = switch (kind) {
            case UNKNOWN_TOOL, INVALID_ARGUMENT, PROTOCOL -> Status.INVALID_ARGUMENT;
            case RUN_NOT_FOUND, NOT_FOUND -> Status.NOT_FOUND;
            case RUN_NOT_ACTIVE -> Status.FAILED_PRECONDITION;
            case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
            case RESULT_TOO_LARGE -> Status.RESOURCE_EXHAUSTED;
            case INTERNAL -> Status.INTERNAL;
        };
        observer.onError(status.withDescription(kind.name()).asRuntimeException());
    }

    private AgentToolErrorKind businessKind(BusinessException exception) {
        return switch (exception.errorCode().status().value()) {
            case 400 -> AgentToolErrorKind.INVALID_ARGUMENT;
            case 403 -> AgentToolErrorKind.PERMISSION_DENIED;
            case 404 -> AgentToolErrorKind.NOT_FOUND;
            default -> AgentToolErrorKind.INTERNAL;
        };
    }

    private <T> void failAny(StreamObserver<T> observer, String toolName,
                             AgentToolErrorKind kind, long started) {
        metrics.record(toolName, false, kind, started);
        Status status = switch (kind) {
            case UNKNOWN_TOOL, INVALID_ARGUMENT, PROTOCOL -> Status.INVALID_ARGUMENT;
            case RUN_NOT_FOUND, NOT_FOUND -> Status.NOT_FOUND;
            case RUN_NOT_ACTIVE -> Status.FAILED_PRECONDITION;
            case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
            case RESULT_TOO_LARGE -> Status.RESOURCE_EXHAUSTED;
            case INTERNAL -> Status.INTERNAL;
        };
        observer.onError(status.withDescription(kind.name()).asRuntimeException());
    }

    private ToolProposalStatus toProto(AgentToolProposalStatus status) {
        return ToolProposalStatus.valueOf("TOOL_PROPOSAL_STATUS_" + status.name());
    }
}
