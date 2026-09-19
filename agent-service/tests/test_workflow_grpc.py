import socket
from concurrent.futures import ThreadPoolExecutor

import grpc
import pytest
from google.protobuf.struct_pb2 import Struct

from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc as rpc
from devpilot_agent_service.rpc.langgraph_application import LangGraphRuntimeApplication
from devpilot_agent_service.rpc.server import RpcServerConfig, create_application, create_server
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)


@pytest.fixture
def ingress(monkeypatch, tmp_path):
    """Actual TCP Runtime + actual Python Gateway client; Gateway server is a test double."""
    calls = []
    service_key = "test-workflow-service-key"
    monkeypatch.setenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", service_key)

    class Gateway(rpc.DevPilotToolGatewayServicer):
        def ExecuteTool(self, request, context):
            assert (
                dict(context.invocation_metadata())["x-devpilot-agent-service-key"] == service_key
            )
            calls.append(request)
            if request.run_id == "denied-run":
                context.abort(grpc.StatusCode.PERMISSION_DENIED, "must not surface gateway body")
            result = Struct()
            result.update(
                {
                    "external_untrusted_content": True,
                    "items": [{"title": "live task"}],
                    "hits": [{"sourceFile": "architecture.md", "text": "document evidence"}],
                }
            )
            return pb.ExecuteToolResponse(
                tool_call_id=request.tool_call_id,
                result=result,
                status=pb.TOOL_EXECUTION_STATUS_SUCCEEDED,
            )

    with ThreadPoolExecutor(max_workers=4) as executor:
        gateway_server = grpc.server(executor)
        rpc.add_DevPilotToolGatewayServicer_to_server(Gateway(), gateway_server)
        gateway_port = gateway_server.add_insecure_port("127.0.0.1:0")
        gateway_server.start()
        gateway_target = f"127.0.0.1:{gateway_port}"
        gateway_channel = grpc.insecure_channel(
            gateway_target, options=(("grpc.enable_http_proxy", 0),)
        )
        client = JavaToolGatewayClient(
            JavaToolGatewayConfig(target=gateway_target, service_key=service_key),
            channel=gateway_channel,
        )
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            runtime_port = probe.getsockname()[1]
        config = RpcServerConfig(
            host="127.0.0.1",
            port=runtime_port,
            model_mode="fake",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        )
        application = create_application(config, tool_client_factory=lambda _: client)
        server = create_server(config, application)
        server.start()
        channel = grpc.insecure_channel(
            config.bind_address, options=(("grpc.enable_http_proxy", 0),)
        )
        try:
            grpc.channel_ready_future(channel).result(timeout=5)
            yield rpc.AgentRuntimeStub(channel), application, calls, service_key, tmp_path
        finally:
            channel.close()
            server.stop(grace=0).wait()
            application.close()
            gateway_channel.close()
            gateway_server.stop(grace=0).wait()


@pytest.mark.parametrize(
    "query, route, expected",
    [
        ("解释一下 CAS", "DIRECT", []),
        ("当前项目有哪些开放任务？", "ONLY_TOOL", ["task.list_open"]),
        ("架构文档为什么要求 Tool 幂等？", "ONLY_RAG", ["knowledge.search"]),
        ("结合开放任务和架构文档分析当前风险", "HYBRID", ["task.list_open", "knowledge.search"]),
    ],
)
def test_production_stream_ingress_routes_and_reuses_actual_gateway_client(
    ingress, query, route, expected, caplog
):
    stub, application, calls, service_key, tmp_path = ingress
    with caplog.at_level("INFO"):
        events = list(
            stub.StreamRun(
                pb.StreamRunRequest(
                    run_id="run-transport",
                    request_id="request-transport",
                    user_input=query,
                ),
                timeout=5,
            )
        )
    assert isinstance(application, LangGraphRuntimeApplication)
    assert events[0].type == pb.AGENT_EVENT_TYPE_RUN_STARTED
    assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert all(event.event_id == f"run-transport:{event.sequence}" for event in events)
    assert [call.tool_name for call in calls] == expected
    assert all(
        call.run_id == "run-transport" and call.request_id == "request-transport" for call in calls
    )
    assert all(call.tool_call_id for call in calls)
    completed = [e.tool_name for e in events if e.type == pb.AGENT_EVENT_TYPE_TOOL_COMPLETED]
    assert completed == expected
    trace = application.trace_for("run-transport")
    assert trace["planner_route"] == route
    assert trace["planner_confidence"] == 1.0
    assert trace["tool_call_count"] == len(expected)
    assert trace["stop_reason"] == "model_final"
    assert query not in caplog.text
    assert service_key not in caplog.text + str(events)
    assert "system prompt" not in caplog.text
    assert (tmp_path / "runtime.sqlite3").exists()
    assert (tmp_path / "runtime.sqlite3.langgraph").exists()


def test_unary_ingress_and_duplicate_run_guard(ingress):
    stub, application, calls, _, _ = ingress
    request = pb.StartRunRequest(run_id="unary", request_id="request", user_input="开放任务")
    result = stub.StartRun(request, timeout=5)
    assert result.status == pb.RUN_STATUS_SUCCEEDED
    assert result.run_id == "unary"
    assert [call.tool_name for call in calls] == ["task.list_open"]
    with pytest.raises(grpc.RpcError) as error:
        stub.StartRun(request, timeout=5)
    assert error.value.code() == grpc.StatusCode.ALREADY_EXISTS
    assert len(calls) == 1
    assert application.trace_for("unary")["planner_route"] == "ONLY_TOOL"


@pytest.mark.parametrize("operation", ["ResumeRun", "ResumeApproval"])
def test_resume_unknown_run_is_rejected(ingress, operation):
    stub, _, calls, _, _ = ingress
    request_type = getattr(pb, operation + "Request")
    arguments = {"run_id": "run", "request_id": "request"}
    if operation == "ResumeApproval":
        arguments["proposal_id"] = "proposal-1"
    with pytest.raises(grpc.RpcError) as error:
        response = getattr(stub, operation)(request_type(**arguments), timeout=5)
        if operation != "CancelRun":
            list(response)
    assert error.value.code() == grpc.StatusCode.NOT_FOUND
    assert error.value.details() == "RUN_NOT_FOUND"
    assert not calls


def test_cancel_unknown_run_is_not_found(ingress):
    stub, _, _, _, _ = ingress
    response = stub.CancelRun(pb.CancelRunRequest(run_id="missing", request_id="request"))
    assert response.status == pb.CANCEL_RUN_STATUS_NOT_FOUND


def test_gateway_permission_denial_becomes_single_safe_failed_terminal(ingress):
    stub, _, calls, _, _ = ingress
    events = list(
        stub.StreamRun(
            pb.StreamRunRequest(
                run_id="denied-run",
                request_id="request",
                user_input="开放任务",
            ),
            timeout=5,
        )
    )
    terminals = [
        event
        for event in events
        if event.type
        in {
            pb.AGENT_EVENT_TYPE_RUN_FAILED,
            pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
        }
    ]
    assert len(terminals) == 1
    assert terminals[0].type == pb.AGENT_EVENT_TYPE_RUN_FAILED
    assert terminals[0].failure_kind == "TOOL_ERROR"
    assert "gateway body" not in str(events)
    assert len(calls) == 1


def test_runtime_has_no_mode_selector():
    assert not hasattr(RpcServerConfig.from_env({}), "runtime_mode")


def test_java_supplied_scope_is_persisted_and_isolated_across_runs(ingress):
    stub, application, _, _, _ = ingress
    scope = pb.AgentExecutionScope(workspace_id=1, project_id=2, actor_user_id=3)
    first = list(stub.StreamRun(pb.StreamRunRequest(
        run_id="memory-one", request_id="request-one",
        user_input="以后请使用中文回答", execution_scope=scope,
    ), timeout=5))
    assert first[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
    second = list(stub.StreamRun(pb.StreamRunRequest(
        run_id="memory-two", request_id="request-two",
        user_input="请回答我刚才的偏好", execution_scope=scope,
    ), timeout=5))
    assert second[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
    assert application.trace_for("memory-two")["memory_recalled"] == 1
    other = list(stub.StreamRun(pb.StreamRunRequest(
        run_id="memory-other", request_id="request-other",
        user_input="请回答我刚才的偏好",
        execution_scope=pb.AgentExecutionScope(
            workspace_id=1, project_id=2, actor_user_id=4,
        ),
    ), timeout=5))
    assert other[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
    assert application.trace_for("memory-other")["memory_recalled"] == 0
