from types import SimpleNamespace

import grpc
import pytest
from google.protobuf.struct_pb2 import Struct

from devpilot_agent_service.rpc.circuit_breaker import (
    CircuitState,
    ConsecutiveFailureCircuitBreaker,
)
from devpilot_agent_service.rpc.generated import agent_runtime_pb2
from devpilot_agent_service.rpc.tool_gateway_client import (
    SERVICE_KEY_HEADER,
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
    JavaToolGatewayError,
    JavaToolGatewayFailureKind,
)
from devpilot_agent_service.runtime.context import RunContext


class FakeChannel:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


class FakeStub:
    def __init__(self, response: object) -> None:
        self.response = response
        self.calls: list[tuple[object, float, tuple[tuple[str, str], ...]]] = []

    def ExecuteTool(self, request: object, *, timeout: float, metadata: tuple) -> object:
        self.calls.append((request, timeout, metadata))
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class FakeRpcError(grpc.RpcError):
    def __init__(self, code: grpc.StatusCode) -> None:
        self._code = code

    def code(self) -> grpc.StatusCode:
        return self._code


def success_response(*, call_id: str = "call-1", payload: dict | None = None):
    result = Struct()
    result.update(payload or {"items": [], "external_untrusted_content": True})
    return agent_runtime_pb2.ExecuteToolResponse(
        result_id="result-1",
        tool_call_id=call_id,
        status=agent_runtime_pb2.TOOL_EXECUTION_STATUS_SUCCEEDED,
        result=result,
    )


def client_with(
    monkeypatch: pytest.MonkeyPatch,
    response: object,
    *,
    max_result: int = 65_536,
    circuit_breaker: ConsecutiveFailureCircuitBreaker | None = None,
):
    stub = FakeStub(response)
    monkeypatch.setattr(
        "devpilot_agent_service.rpc.tool_gateway_client.agent_runtime_pb2_grpc.DevPilotToolGatewayStub",
        lambda channel: stub,
    )
    config = JavaToolGatewayConfig(
        target="127.0.0.1:50052",
        service_key="0123456789abcdef",
        deadline_seconds=2.5,
        max_result_bytes=max_result,
    )
    return JavaToolGatewayClient(
        config,
        channel=FakeChannel(),
        circuit_breaker=circuit_breaker,
    ), stub, config


def test_execute_propagates_context_call_id_struct_deadline_and_service_key(monkeypatch) -> None:
    client, stub, _ = client_with(monkeypatch, success_response())

    result = client.execute(
        RunContext("run-1", "request-1"), "call-1", "task.list_open", {"limit": 10}
    )

    request, timeout, metadata = stub.calls[0]
    assert request.run_id == "run-1"
    assert request.request_id == "request-1"
    assert request.tool_call_id == "call-1"
    assert request.arguments.fields["limit"].number_value == 10
    assert timeout == 2.5
    assert metadata == ((SERVICE_KEY_HEADER, "0123456789abcdef"),)
    assert result == {"items": [], "external_untrusted_content": True}


@pytest.mark.parametrize(
    ("code", "kind"),
    [
        (grpc.StatusCode.UNAUTHENTICATED, JavaToolGatewayFailureKind.UNAUTHENTICATED),
        (grpc.StatusCode.PERMISSION_DENIED, JavaToolGatewayFailureKind.PERMISSION_DENIED),
        (grpc.StatusCode.INVALID_ARGUMENT, JavaToolGatewayFailureKind.INVALID_ARGUMENT),
        (grpc.StatusCode.DEADLINE_EXCEEDED, JavaToolGatewayFailureKind.DEADLINE),
    ],
)
def test_rpc_status_is_mapped_without_description(monkeypatch, code, kind) -> None:
    client, _, _ = client_with(monkeypatch, FakeRpcError(code))

    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})

    assert captured.value.kind is kind


@pytest.mark.parametrize(
    "code",
    [grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED],
)
def test_transport_failure_opens_circuit_and_next_call_fast_fails(monkeypatch, code) -> None:
    circuit = ConsecutiveFailureCircuitBreaker(1, 10)
    client, stub, _ = client_with(
        monkeypatch,
        FakeRpcError(code),
        circuit_breaker=circuit,
    )

    with pytest.raises(JavaToolGatewayError):
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-2", "task.list_open", {})

    assert circuit.state is CircuitState.OPEN
    assert captured.value.kind is JavaToolGatewayFailureKind.CIRCUIT_OPEN
    assert len(stub.calls) == 1


@pytest.mark.parametrize(
    "code",
    [grpc.StatusCode.PERMISSION_DENIED, grpc.StatusCode.INVALID_ARGUMENT],
)
def test_reachable_business_error_does_not_open_circuit(monkeypatch, code) -> None:
    circuit = ConsecutiveFailureCircuitBreaker(1, 10)
    client, stub, _ = client_with(
        monkeypatch,
        FakeRpcError(code),
        circuit_breaker=circuit,
    )

    with pytest.raises(JavaToolGatewayError):
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    with pytest.raises(JavaToolGatewayError):
        client.execute(RunContext("run-1", "request-1"), "call-2", "task.list_open", {})

    assert circuit.state is CircuitState.CLOSED
    assert len(stub.calls) == 2


def test_failed_response_call_mismatch_size_and_missing_untrusted_marker(monkeypatch) -> None:
    failed = agent_runtime_pb2.ExecuteToolResponse(
        tool_call_id="call-1",
        status=agent_runtime_pb2.TOOL_EXECUTION_STATUS_FAILED,
        error_kind="UNKNOWN_TOOL",
    )
    client, stub, _ = client_with(monkeypatch, failed)
    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    assert captured.value.kind is JavaToolGatewayFailureKind.UNKNOWN_TOOL

    stub.response = success_response(call_id="different")
    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    assert captured.value.kind is JavaToolGatewayFailureKind.PROTOCOL

    stub.response = success_response(
        payload={"value": "x" * 200, "external_untrusted_content": True}
    )
    client._config = SimpleNamespace(deadline_seconds=1, max_result_bytes=32, service_key="secret")
    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    assert captured.value.kind is JavaToolGatewayFailureKind.RESULT_TOO_LARGE

    stub.response = success_response(payload={"items": []})
    client._config = SimpleNamespace(
        deadline_seconds=1, max_result_bytes=1000, service_key="secret"
    )
    with pytest.raises(JavaToolGatewayError) as captured:
        client.execute(RunContext("run-1", "request-1"), "call-1", "task.list_open", {})
    assert captured.value.kind is JavaToolGatewayFailureKind.PROTOCOL


def test_config_hides_service_key_and_validates_environment() -> None:
    config = JavaToolGatewayConfig.from_env(
        {
            "DEVPILOT_JAVA_TOOL_GRPC_TARGET": "localhost:50052",
            "DEVPILOT_AGENT_TOOL_SERVICE_KEY": "0123456789abcdef",
        }
    )

    assert "0123456789abcdef" not in repr(config)
    with pytest.raises(ValueError):
        JavaToolGatewayConfig.from_env({})
