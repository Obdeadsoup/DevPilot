import json
import socket
import threading

import grpc
import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.graph.checkpoint import GraphCheckpointStore
from devpilot_agent_service.harness.demo import DemoGatewayClient
from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.memory.store import MemoryScope, MemoryStore
from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc as rpc
from devpilot_agent_service.rpc.langgraph_application import LangGraphRuntimeApplication
from devpilot_agent_service.rpc.server import RpcServerConfig, create_server
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    ApprovalRequired,
    ModelInvocationError,
    ResumeRejected,
    RunCancelled,
)
from devpilot_agent_service.runtime.persistence import RunStatus
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository
from devpilot_agent_service.tools.base import ToolProposal, ToolProposalResolution
from devpilot_agent_service.tools.devpilot import (
    CreateTaskTool,
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.registry import ToolRegistry


def planner(route):
    return FakeModel(
        [ModelResponse.final(json.dumps({
            "route": route, "rewritten_query": "query", "confidence": 0.9,
            "reason_code": "GENERAL",
        }))]
    )


def application(tmp_path, model, route="DIRECT", client=None):
    client = client or DemoGatewayClient()
    registry = ToolRegistry()
    for tool in (
        ProjectSummaryTool, ListOpenTasksTool, RecentProjectActivityTool,
        KnowledgeSearchTool, CreateTaskTool,
    ):
        registry.register(tool(client))
    path = tmp_path / "runtime.sqlite3"
    checkpoint = GraphCheckpointStore(str(path) + ".langgraph")
    memory = MemoryStore(str(path) + ".memory")
    workflow = WorkflowRuntime(
        model, registry, lambda: FakeModel([]),
        planner_model=planner(route), checkpointer=checkpoint.saver, memory_store=memory,
    )
    app = LangGraphRuntimeApplication(
        workflow, SQLiteAgentRuntimeRepository(path), registry, memory,
        close_callback=lambda: (checkpoint.close(), memory.close()),
    )
    return app


def test_checkpoint_resume_uses_graph_boundary_after_temporary_model_failure(tmp_path):
    context = RunContext("resume-1", "request-1")
    first = application(
        tmp_path,
        FakeModel([ProviderError(ProviderErrorKind.TIMEOUT)]),
    )
    with pytest.raises(ModelInvocationError):
        first.start_run("question", run_context=context)
    assert first._repository.get_run(context.run_id).failure_code == "TEMPORARY_MODEL_ERROR"
    first.close()

    resumed = application(tmp_path, FakeModel([ModelResponse.final("recovered")]))
    prepared = resumed.prepare_resume(context)
    assert resumed.execute_prepared(prepared).final_answer == "recovered"
    assert resumed._repository.get_run(context.run_id).status is RunStatus.SUCCEEDED
    with pytest.raises(ResumeRejected):
        resumed.prepare_resume(context)
    resumed.close()


def test_cancel_intent_is_persistent_before_execution(tmp_path):
    app = application(tmp_path, FakeModel([ModelResponse.final("never")]))
    context = RunContext("cancel-1", "request-1")
    prepared = app.prepare_run("question", context)
    assert app.request_cancel(context.run_id, context.request_id).accepted
    with pytest.raises(RunCancelled):
        app.execute_prepared(prepared)
    assert app._repository.get_run(context.run_id).status is RunStatus.CANCELLED
    app.close()


def test_unified_grpc_stream_cancel_has_one_cancelled_terminal(tmp_path):
    release = threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            assert release.wait(timeout=5)
            return ModelResponse.final("should not commit")

    app = application(tmp_path, BlockingModel())
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = create_server(
        RpcServerConfig(
            host="127.0.0.1", port=port, model_mode="fake",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        ),
        app,
    )
    server.start()
    channel = grpc.insecure_channel(
        f"127.0.0.1:{port}", options=(("grpc.enable_http_proxy", 0),)
    )
    try:
        stub = rpc.AgentRuntimeStub(channel)
        stream = stub.StreamRun(pb.StreamRunRequest(
            run_id="grpc-cancel", request_id="request", user_input="question"
        ), timeout=8)
        first = next(stream)
        assert first.type == pb.AGENT_EVENT_TYPE_RUN_STARTED
        decision = stub.CancelRun(pb.CancelRunRequest(
            run_id="grpc-cancel", request_id="request"
        ), timeout=3)
        assert decision.accepted
        release.set()
        events = [first, *stream]
        assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_CANCELLED
        assert sum(
            event.type in {
                pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
                pb.AGENT_EVENT_TYPE_RUN_FAILED,
                pb.AGENT_EVENT_TYPE_RUN_CANCELLED,
            }
            for event in events
        ) == 1
        assert app._repository.get_run("grpc-cancel").status is RunStatus.CANCELLED
    finally:
        release.set()
        channel.close()
        server.stop(grace=0).wait()
        app.close()


def test_unified_grpc_resume_after_restart_does_not_replan(tmp_path):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    config = RpcServerConfig(
        host="127.0.0.1", port=port, model_mode="fake",
        runtime_db_path=str(tmp_path / "runtime.sqlite3"),
    )
    first_app = application(
        tmp_path, FakeModel([ProviderError(ProviderErrorKind.TIMEOUT)])
    )
    first_server = create_server(config, first_app)
    first_server.start()
    first_channel = grpc.insecure_channel(
        f"127.0.0.1:{port}", options=(("grpc.enable_http_proxy", 0),)
    )
    try:
        events = list(rpc.AgentRuntimeStub(first_channel).StreamRun(
            pb.StreamRunRequest(
                run_id="grpc-resume", request_id="request", user_input="question"
            ), timeout=5,
        ))
        assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_FAILED
        assert first_app._repository.get_run("grpc-resume").retryable
    finally:
        first_channel.close()
        first_server.stop(grace=0).wait()
        first_app.close()

    resumed_model = FakeModel([ModelResponse.final("recovered")])
    second_app = application(tmp_path, resumed_model)
    second_server = create_server(config, second_app)
    second_server.start()
    second_channel = grpc.insecure_channel(
        f"127.0.0.1:{port}", options=(("grpc.enable_http_proxy", 0),)
    )
    try:
        events = list(rpc.AgentRuntimeStub(second_channel).ResumeRun(
            pb.ResumeRunRequest(run_id="grpc-resume", request_id="request"), timeout=5,
        ))
        assert events[0].type == pb.AGENT_EVENT_TYPE_RUN_RESUMED
        assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
        assert events[-1].final_output == "recovered"
        assert len(resumed_model.calls) == 1
    finally:
        second_channel.close()
        second_server.stop(grace=0).wait()
        second_app.close()


class ProposalGateway(DemoGatewayClient):
    def __init__(self):
        super().__init__()
        self.proposals = []
        self.status = "PENDING"

    def create_proposal(self, context, call_id, name, arguments):
        self.proposals.append((context, call_id, name, dict(arguments)))
        return ToolProposal("proposal-1", call_id, name, "PENDING", "2099-01-01T00:00:00Z")

    def get_proposal(self, context, proposal_id):
        return ToolProposalResolution(
            proposal_id, "call-1", "task.create", self.status, {"taskId": 41}
        )


def test_approval_interrupt_survives_restart_and_uses_java_resolution(tmp_path):
    gateway = ProposalGateway()
    context = RunContext("approval-1", "request-1")
    app = application(
        tmp_path,
        FakeModel([ModelResponse.request_tools([
            ToolCall("call-1", "task.create", {"title": "bounded"})
        ])]),
        "ONLY_TOOL",
        gateway,
    )
    with pytest.raises(ApprovalRequired) as waiting:
        app.start_run("create task", run_context=context)
    assert waiting.value.proposal.proposal_id == "proposal-1"
    assert app._repository.get_run(context.run_id).status is RunStatus.WAITING_APPROVAL
    assert len(gateway.proposals) == 1
    app.close()

    gateway.status = "EXECUTED"
    resumed = application(
        tmp_path,
        FakeModel([ModelResponse.final("task created"), ModelResponse.final("task created")]),
        "ONLY_TOOL",
        gateway,
    )
    prepared = resumed.prepare_approval_resume(context, "proposal-1")
    assert resumed.execute_prepared(prepared).final_answer == "task created"
    assert resumed._repository.get_run(context.run_id).status is RunStatus.SUCCEEDED
    assert len(gateway.proposals) == 1
    resumed.close()


def test_memory_scope_isolated_and_writes_only_explicit_user_text(tmp_path):
    app = application(tmp_path, FakeModel([ModelResponse.final("ok")]))
    scope = MemoryScope(1, 2, 3)
    app.start_run(
        "以后请使用中文回答",
        run_context=RunContext("memory-1", "request-1", scope),
    )
    assert "中文" in app._memory.recall(scope, "请回答")
    assert app._memory.recall(MemoryScope(1, 2, 4), "请回答") == ""
    assert app._memory.scope_for_run("memory-1", "request-1") == scope
    assert app._memory.extract_explicit("当前 task 有 17 个") is None
    app.close()
    model = FakeModel([ModelResponse.final("continued")])
    second = application(tmp_path, model)
    second.start_run(
        "我刚才的偏好是什么？",
        run_context=RunContext("memory-2", "request-2", scope),
    )
    model_input = [message.content for message in model.calls[0].messages]
    assert any("Remembered user/project preferences" in content for content in model_input)
    assert any("以后请使用中文回答" in content for content in model_input)
    assert model_input[-1] == "我刚才的偏好是什么？"
    assert second._memory.recall(MemoryScope(1, 9, 3), "请回答") == ""
    second._memory.upsert(
        scope, key="bad", kind="USER_PREFERENCE", content="token=sk-abcdefgh12345",
        source_run_id="memory-2", confidence=.9,
    )
    assert "sk-abcdefgh12345" not in second._memory.recall(scope, "token")
    memories = second._memory.list_memories(scope)
    assert len(memories) == 1
    assert not second._memory.upsert(
        scope, key=memories[0]["memory_key"], kind="USER_PREFERENCE",
        content=memories[0]["content"], source_run_id="memory-2", confidence=.9,
    )
    assert not second._memory.delete(MemoryScope(1, 2, 4), memories[0]["memory_key"])
    assert second._memory.delete(scope, memories[0]["memory_key"])
    assert second._memory.list_memories(scope) == ()
    second.close()
