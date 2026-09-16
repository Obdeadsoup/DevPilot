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
from devpilot_agent_service.runtime.context import RunContext


def test_server_config_uses_safe_defaults_and_environment_overrides() -> None:
    default = RpcServerConfig.from_env({})
    overridden = RpcServerConfig.from_env(
        {
            "AGENT_GRPC_HOST": "127.0.0.1",
            "AGENT_GRPC_PORT": "55051",
            "AGENT_MODEL_MODE": "FAKE",
            "AGENT_RUNTIME_DB_PATH": "custom/runtime.sqlite3",
            "AGENT_FAKE_TOOL_NAME": "project.get_summary",
        }
    )

    assert default == RpcServerConfig(
        host=DEFAULT_GRPC_HOST,
        port=DEFAULT_GRPC_PORT,
        model_mode=DEFAULT_MODEL_MODE,
    )
    assert overridden.bind_address == "127.0.0.1:55051"
    assert overridden.model_mode == "fake"
    assert overridden.runtime_db_path == "custom/runtime.sqlite3"
    assert overridden.fake_tool_name == "project.get_summary"


@pytest.mark.parametrize(
    "environment",
    [
        {"AGENT_GRPC_PORT": "not-a-number"},
        {"AGENT_GRPC_PORT": "0"},
        {"AGENT_GRPC_PORT": "65536"},
        {"AGENT_MODEL_MODE": "unknown"},
        {"AGENT_MODEL_MODE": "fake", "AGENT_FAKE_TOOL_NAME": "unknown.tool"},
        {"AGENT_GRPC_HOST": " "},
        {"AGENT_RUNTIME_DB_PATH": " "},
        {"AGENT_RUNTIME_DB_PATH": ":memory:"},
    ],
)
def test_server_config_rejects_invalid_values(environment: dict[str, str]) -> None:
    with pytest.raises(ValueError):
        RpcServerConfig.from_env(environment)


def test_fake_mode_is_deterministic_and_still_uses_agent_loop(tmp_path) -> None:
    result = create_application(
        RpcServerConfig(model_mode="fake", runtime_db_path=str(tmp_path / "runtime.sqlite3"))
    ).start_run("hello")

    assert result.final_answer == "fake:hello"
    assert len(result.trace) == 1


def test_fake_tool_mode_exercises_remote_gateway_before_final_output(
    monkeypatch, tmp_path
) -> None:
    class FakeGatewayClient:
        def __init__(self) -> None:
            self.calls: list[tuple[str, str]] = []
            self.closed = False

        def execute(self, context, call_id, name, arguments):
            self.calls.append((context.run_id, name))
            return {"project": "DP", "external_untrusted_content": True}

        def close(self) -> None:
            self.closed = True

    client = FakeGatewayClient()
    monkeypatch.setenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", "fake-test-service-key")
    application = create_application(
        RpcServerConfig(
            model_mode="fake",
            fake_tool_name="project.get_summary",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        ),
        tool_client_factory=lambda _config: client,
    )
    result = application.start_run(
        "summarize",
        run_context=RunContext("run-1", "request-1"),
    )
    application.close()

    assert result.final_answer == "fake-tool:project.get_summary:ok"
    assert [step.tool_names for step in result.trace] == [("project.get_summary",), ()]
    assert client.calls == [("run-1", "project.get_summary")]
    assert client.closed is True


def test_server_bootstrap_registers_real_tcp_server(tmp_path) -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    server = create_server(
        RpcServerConfig(
            host="127.0.0.1",
            port=port,
            model_mode="fake",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        )
    )
    server.start()
    try:
        assert server is not None
    finally:
        server.stop(grace=0).wait()


def test_stream_run_uses_real_tcp_and_cancel_reports_not_found(tmp_path) -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    server = create_server(
        RpcServerConfig(
            host="127.0.0.1",
            port=port,
            model_mode="fake",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        )
    )
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
