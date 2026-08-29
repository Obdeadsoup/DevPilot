import socket

import grpc
import pytest

from devpilot_agent_service.rpc.generated import agent_runtime_pb2, agent_runtime_pb2_grpc
from devpilot_agent_service.rpc.server import (
    DEFAULT_GRPC_HOST,
    DEFAULT_GRPC_PORT,
    DEFAULT_MODEL_MODE,
    RpcServerConfig,
    create_application,
    create_server,
)


def test_server_config_uses_safe_defaults_and_environment_overrides() -> None:
    default = RpcServerConfig.from_env({})
    overridden = RpcServerConfig.from_env(
        {
            "AGENT_GRPC_HOST": "127.0.0.1",
            "AGENT_GRPC_PORT": "55051",
            "AGENT_MODEL_MODE": "FAKE",
        }
    )

    assert default == RpcServerConfig(
        host=DEFAULT_GRPC_HOST,
        port=DEFAULT_GRPC_PORT,
        model_mode=DEFAULT_MODEL_MODE,
    )
    assert overridden.bind_address == "127.0.0.1:55051"
    assert overridden.model_mode == "fake"


@pytest.mark.parametrize(
    "environment",
    [
        {"AGENT_GRPC_PORT": "not-a-number"},
        {"AGENT_GRPC_PORT": "0"},
        {"AGENT_GRPC_PORT": "65536"},
        {"AGENT_MODEL_MODE": "unknown"},
        {"AGENT_GRPC_HOST": " "},
    ],
)
def test_server_config_rejects_invalid_values(environment: dict[str, str]) -> None:
    with pytest.raises(ValueError):
        RpcServerConfig.from_env(environment)


def test_fake_mode_is_deterministic_and_still_uses_agent_loop() -> None:
    result = create_application(RpcServerConfig(model_mode="fake")).start_run("hello")

    assert result.final_answer == "fake:hello"
    assert len(result.trace) == 1


def test_server_bootstrap_registers_real_tcp_server() -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    server = create_server(RpcServerConfig(host="127.0.0.1", port=port, model_mode="fake"))
    server.start()
    try:
        assert server is not None
    finally:
        server.stop(grace=0).wait()


def test_stream_run_uses_real_tcp_and_cancel_reports_not_found() -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    server = create_server(RpcServerConfig(host="127.0.0.1", port=port, model_mode="fake"))
    server.start()
    channel = grpc.insecure_channel(
        f"127.0.0.1:{port}",
        options=(("grpc.enable_http_proxy", 0),),
    )
    stub = agent_runtime_pb2_grpc.AgentRuntimeStub(channel)
    tool_gateway = agent_runtime_pb2_grpc.DevPilotToolGatewayStub(channel)
    try:
        grpc.channel_ready_future(channel).result(timeout=5)
        cancel = stub.CancelRun(
            agent_runtime_pb2.CancelRunRequest(run_id="run-1", request_id="request-1"),
            timeout=1,
        )
        assert cancel.accepted is False
        assert cancel.status == agent_runtime_pb2.CANCEL_RUN_STATUS_NOT_FOUND

        stream_events = list(
            stub.StreamRun(
                agent_runtime_pb2.StreamRunRequest(
                    run_id="run-1", request_id="request-1", user_input="hello"
                ),
                timeout=2,
            )
        )
        assert [event.type for event in stream_events] == [
            agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED,
            agent_runtime_pb2.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
            agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
        ]
        assert stream_events[-1].final_output == "fake:hello"

        with pytest.raises(grpc.RpcError) as tool_error:
            tool_gateway.ExecuteTool(
                agent_runtime_pb2.ExecuteToolRequest(request_id="request-1"),
                timeout=1,
            )
        assert tool_error.value.code() == grpc.StatusCode.UNIMPLEMENTED
    finally:
        channel.close()
        server.stop(grace=0).wait()
