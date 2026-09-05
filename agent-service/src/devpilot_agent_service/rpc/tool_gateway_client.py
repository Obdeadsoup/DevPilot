"""Python → Java DevPilotToolGateway Unary Client。"""

import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Self

import grpc
from google.protobuf.json_format import MessageToDict
from google.protobuf.struct_pb2 import Struct

from devpilot_agent_service.rpc.circuit_breaker import (
    CircuitOpenError,
    ConsecutiveFailureCircuitBreaker,
)
from devpilot_agent_service.rpc.generated import agent_runtime_pb2, agent_runtime_pb2_grpc
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.tools.base import JsonValue, ToolProposal, ToolProposalResolution

SERVICE_KEY_HEADER = "x-devpilot-agent-service-key"


class JavaToolGatewayFailureKind(StrEnum):
    UNAUTHENTICATED = "UNAUTHENTICATED"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    DEADLINE = "DEADLINE"
    UNAVAILABLE = "UNAVAILABLE"
    RESULT_TOO_LARGE = "RESULT_TOO_LARGE"
    PROTOCOL = "PROTOCOL"
    INTERNAL = "INTERNAL"
    UNKNOWN_TOOL = "UNKNOWN_TOOL"
    RUN_NOT_FOUND = "RUN_NOT_FOUND"
    RUN_NOT_ACTIVE = "RUN_NOT_ACTIVE"
    NOT_FOUND = "NOT_FOUND"
    CIRCUIT_OPEN = "CIRCUIT_OPEN"


class JavaToolGatewayError(RuntimeError):
    """稳定 Tool RPC 错误；不包含 gRPC description、参数、结果或 service key。"""

    def __init__(self, kind: JavaToolGatewayFailureKind) -> None:
        super().__init__(kind.value)
        self.kind = kind
        # 只有明确暂时性依赖故障允许 Runtime 显式重试，权限/业务/协议错误不自动放行。
        self.retryable = kind in {
            JavaToolGatewayFailureKind.DEADLINE,
            JavaToolGatewayFailureKind.UNAVAILABLE,
            JavaToolGatewayFailureKind.CIRCUIT_OPEN,
        }


@dataclass(frozen=True, slots=True)
class JavaToolGatewayConfig:
    """进程级 Channel 配置；service key 从 repr 隐藏且只进入 gRPC metadata。"""

    target: str = "127.0.0.1:50052"
    service_key: str = field(default="", repr=False)
    deadline_seconds: float = 3.0
    max_message_bytes: int = 65_536
    max_result_bytes: int = 65_536
    circuit_failure_threshold: int = 3
    circuit_open_seconds: float = 10.0

    def __post_init__(self) -> None:
        if not isinstance(self.target, str) or not self.target.strip():
            raise ValueError("tool gateway target must not be blank")
        if not isinstance(self.service_key, str) or len(self.service_key) < 16:
            raise ValueError("tool gateway service key must contain at least 16 characters")
        if self.deadline_seconds <= 0:
            raise ValueError("tool gateway deadline must be positive")
        if self.max_message_bytes < 1 or self.max_result_bytes < 1:
            raise ValueError("tool gateway message limits must be positive")
        if self.circuit_failure_threshold < 1 or self.circuit_open_seconds <= 0:
            raise ValueError("tool gateway circuit configuration must be positive")

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> Self:
        source = os.environ if environ is None else environ
        try:
            deadline = float(source.get("DEVPILOT_JAVA_TOOL_GRPC_DEADLINE_SECONDS", "3"))
            max_message = int(source.get("DEVPILOT_JAVA_TOOL_GRPC_MAX_MESSAGE_BYTES", "65536"))
            max_result = int(source.get("DEVPILOT_JAVA_TOOL_GRPC_MAX_RESULT_BYTES", "65536"))
            circuit_threshold = int(
                source.get("DEVPILOT_JAVA_TOOL_CIRCUIT_FAILURE_THRESHOLD", "3")
            )
            circuit_open = float(source.get("DEVPILOT_JAVA_TOOL_CIRCUIT_OPEN_SECONDS", "10"))
        except ValueError as error:
            raise ValueError("tool gateway numeric configuration is invalid") from error
        return cls(
            target=source.get("DEVPILOT_JAVA_TOOL_GRPC_TARGET", "127.0.0.1:50052").strip(),
            service_key=source.get("DEVPILOT_AGENT_TOOL_SERVICE_KEY", ""),
            deadline_seconds=deadline,
            max_message_bytes=max_message,
            max_result_bytes=max_result,
            circuit_failure_threshold=circuit_threshold,
            circuit_open_seconds=circuit_open,
        )


class JavaToolGatewayClient:
    """复用一个长生命周期 Channel；AgentLoop 仍只调用 Tool interface，不感知 gRPC。"""

    def __init__(
        self,
        config: JavaToolGatewayConfig,
        *,
        channel: grpc.Channel | None = None,
        circuit_breaker: ConsecutiveFailureCircuitBreaker | None = None,
    ) -> None:
        self._config = config
        self._owns_channel = channel is None
        self._channel = channel or grpc.insecure_channel(
            config.target,
            options=(
                ("grpc.max_send_message_length", config.max_message_bytes),
                ("grpc.max_receive_message_length", config.max_message_bytes),
            ),
        )
        self._stub = agent_runtime_pb2_grpc.DevPilotToolGatewayStub(self._channel)
        self._circuit_breaker = circuit_breaker or ConsecutiveFailureCircuitBreaker(
            config.circuit_failure_threshold,
            config.circuit_open_seconds,
        )

    def execute(
        self,
        run_context: RunContext,
        tool_call_id: str,
        tool_name: str,
        arguments: Mapping[str, object],
    ) -> JsonValue:
        argument_struct = Struct()
        try:
            argument_struct.update(dict(arguments))
        except (TypeError, ValueError) as error:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.INVALID_ARGUMENT) from error
        request = agent_runtime_pb2.ExecuteToolRequest(
            request_id=run_context.request_id,
            run_id=run_context.run_id,
            tool_call_id=tool_call_id,
            tool_name=tool_name,
            arguments=argument_struct,
        )
        response = self._invoke(self._stub.ExecuteTool, request)

        if response.tool_call_id != tool_call_id:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        if response.status == agent_runtime_pb2.TOOL_EXECUTION_STATUS_FAILED:
            raise JavaToolGatewayError(_failure_kind(response.error_kind))
        if response.status != agent_runtime_pb2.TOOL_EXECUTION_STATUS_SUCCEEDED:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        if response.result.ByteSize() > self._config.max_result_bytes:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.RESULT_TOO_LARGE)
        result = MessageToDict(response.result, preserving_proto_field_name=True)
        if result.get("external_untrusted_content") is not True:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        return result

    def create_proposal(
        self,
        run_context: RunContext,
        tool_call_id: str,
        tool_name: str,
        arguments: Mapping[str, object],
    ) -> ToolProposal:
        argument_struct = Struct()
        try:
            argument_struct.update(dict(arguments))
        except (TypeError, ValueError) as error:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.INVALID_ARGUMENT) from error
        request = agent_runtime_pb2.CreateToolProposalRequest(
            request_id=run_context.request_id,
            run_id=run_context.run_id,
            tool_call_id=tool_call_id,
            tool_name=tool_name,
            arguments=argument_struct,
        )
        response = self._invoke(self._stub.CreateToolProposal, request)
        if response.tool_call_id != tool_call_id or response.status != "PENDING_APPROVAL":
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        return ToolProposal(
            response.proposal_id,
            response.tool_call_id,
            tool_name,
            response.status,
            response.expires_at,
        )

    def get_proposal(
        self, run_context: RunContext, proposal_id: str
    ) -> ToolProposalResolution:
        response = self._invoke(
            self._stub.GetToolProposal,
            agent_runtime_pb2.GetToolProposalRequest(
                request_id=run_context.request_id,
                run_id=run_context.run_id,
                proposal_id=proposal_id,
            ),
        )
        if response.proposal_id != proposal_id:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        status_name = agent_runtime_pb2.ToolProposalStatus.Name(response.status)
        prefix = "TOOL_PROPOSAL_STATUS_"
        if not status_name.startswith(prefix):
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL)
        result = MessageToDict(response.result, preserving_proto_field_name=True)
        return ToolProposalResolution(
            response.proposal_id,
            response.tool_call_id,
            response.tool_name,
            status_name[len(prefix):],
            result,
        )

    def _invoke(self, method, request):
        try:
            self._circuit_breaker.before_call()
        except CircuitOpenError as error:
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.CIRCUIT_OPEN) from error
        try:
            response = method(
                request,
                timeout=self._config.deadline_seconds,
                metadata=((SERVICE_KEY_HEADER, self._config.service_key),),
            )
        except grpc.RpcError as error:
            kind = _map_rpc_code(error.code())
            if kind in {
                JavaToolGatewayFailureKind.DEADLINE,
                JavaToolGatewayFailureKind.UNAVAILABLE,
            }:
                self._circuit_breaker.record_failure()
            else:
                self._circuit_breaker.record_success()
            raise JavaToolGatewayError(kind) from error
        except Exception as error:
            self._circuit_breaker.record_success()
            raise JavaToolGatewayError(JavaToolGatewayFailureKind.INTERNAL) from error

        # 收到合法 gRPC response 即证明依赖可达；业务/协议分类不能把依赖熔断。
        self._circuit_breaker.record_success()
        return response

    def close(self) -> None:
        if self._owns_channel:
            self._channel.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def _map_rpc_code(code: grpc.StatusCode) -> JavaToolGatewayFailureKind:
    return {
        grpc.StatusCode.UNAUTHENTICATED: JavaToolGatewayFailureKind.UNAUTHENTICATED,
        grpc.StatusCode.PERMISSION_DENIED: JavaToolGatewayFailureKind.PERMISSION_DENIED,
        grpc.StatusCode.INVALID_ARGUMENT: JavaToolGatewayFailureKind.INVALID_ARGUMENT,
        grpc.StatusCode.DEADLINE_EXCEEDED: JavaToolGatewayFailureKind.DEADLINE,
        grpc.StatusCode.UNAVAILABLE: JavaToolGatewayFailureKind.UNAVAILABLE,
        grpc.StatusCode.RESOURCE_EXHAUSTED: JavaToolGatewayFailureKind.RESULT_TOO_LARGE,
        grpc.StatusCode.NOT_FOUND: JavaToolGatewayFailureKind.NOT_FOUND,
        grpc.StatusCode.FAILED_PRECONDITION: JavaToolGatewayFailureKind.RUN_NOT_ACTIVE,
    }.get(code, JavaToolGatewayFailureKind.INTERNAL)


def _failure_kind(raw: str) -> JavaToolGatewayFailureKind:
    try:
        return JavaToolGatewayFailureKind(raw)
    except ValueError as error:
        raise JavaToolGatewayError(JavaToolGatewayFailureKind.PROTOCOL) from error
