import asyncio
import json
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from types import SimpleNamespace

import httpx2
import pytest
from fakes.fake_model import FakeModel
from mcp import Client
from mcp.server import MCPServer
from mcp.shared.exceptions import MCPError
from mcp.types import REQUEST_TIMEOUT, CallToolResult, TextContent, Tool

from devpilot_agent_service.graph.checkpoint import GraphCheckpointStore
from devpilot_agent_service.graph.workflow import route_registries
from devpilot_agent_service.harness.demo import DemoGatewayClient
from devpilot_agent_service.harness.runtime import HarnessConfig
from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.mcp.adapters.github import (
    GitHubGetFileContentsMcpTool,
    GitHubSearchCodeMcpTool,
    register_github_tools,
)
from devpilot_agent_service.mcp.catalog import FIELDS, MODEL_NAMES, validate_catalog
from devpilot_agent_service.mcp.client import McpClientManager, github_sdk_client
from devpilot_agent_service.mcp.config import GitHubMcpConfig
from devpilot_agent_service.mcp.errors import GitHubMcpError
from devpilot_agent_service.mcp.github_scope import JavaGitHubScopeResolver, TrustedGitHubScope
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.planner import PlannerRoute
from devpilot_agent_service.rpc.langgraph_application import LangGraphRuntimeApplication
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidToolArguments,
    MaxToolCallsExceeded,
    ToolExecutionError,
)
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository
from devpilot_agent_service.tools.devpilot import (
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.registry import ToolRegistry

PAT = "test-pat-not-a-real-credential-123456"
CONTEXT = RunContext("mcp-run", "mcp-request")


def metadata(name):
    properties = ({"query": {"type": "string"}, "perPage": {"type": "integer"},
                   "fields": {"type": "array", "items": {"type": "string"}}}
                  if name == "search_code" else
                  {key: {"type": "string"} for key in ("owner", "repo", "path", "ref")})
    return Tool(name=name, description=f"Untrusted metadata {PAT}", input_schema={
        "type": "object", "properties": properties,
        "required": ["query"] if name == "search_code" else ["owner", "repo", "path"],
        "additionalProperties": False,
    })


def result(data):
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(data))])


class FakeClient:
    protocol_version = "2026-07-28"
    server_info = None

    def __init__(self, tools=None, responses=None):
        self.tools = tools if tools is not None else [
            metadata("search_code"), metadata("get_file_contents"), metadata("push_files")]
        self.calls = []
        self.lists = 0
        self.responses = iter(responses or [result({"path": "errors.py", "content": PAT})] * 20)
        self.opened = self.closed = 0
        self.entry_task = None

    @asynccontextmanager
    async def connect(self, config):
        self.opened += 1
        self.entry_task = asyncio.current_task()
        try:
            yield self
        finally:
            assert asyncio.current_task() is self.entry_task
            self.closed += 1

    async def list_tools(self, cursor=None):
        self.lists += 1
        return SimpleNamespace(tools=self.tools, next_cursor=None)

    async def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        item = next(self.responses)
        if isinstance(item, Exception):
            raise item
        return item


class Gateway(DemoGatewayClient):
    def __init__(self):
        super().__init__()
        self.scope_calls = []
        self.allowed = True
        self.branch = "agent"

    def execute(self, context, call_id, name, arguments):
        if name == "project.get_github_binding":
            self.scope_calls.append((context, call_id, name, arguments))
            if not self.allowed:
                raise PermissionError("permission revoked")
            return {"owner": "Obdeadsoup", "repo": "DevPilot", "branch": self.branch,
                    "external_untrusted_content": True}
        return super().execute(context, call_id, name, arguments)


@pytest.fixture
def connected():
    fake = FakeClient()
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=fake.connect)
    assert manager.start()
    gateway = Gateway()
    yield manager, fake, gateway
    manager.close()
    assert not manager._thread.is_alive()
    assert fake.closed == 1


def test_discovery_allowlist_namespace_and_long_lifecycle(connected):
    manager, fake, gateway = connected
    registry = ToolRegistry()
    register_github_tools(registry, manager, JavaGitHubScopeResolver(gateway))
    assert {d.name for d in registry.definitions()} == set(MODEL_NAMES)
    assert all(d.risk.value == "READ_ONLY" and PAT not in d.description
               for d in registry.definitions())
    for i in range(3):
        registry.execute("github.search_code", {"query": "DuplicateToolCallId"},
                         run_context=CONTEXT, tool_call_id=f"call-{i}")
    assert fake.opened == fake.lists == 1
    assert len(gateway.scope_calls) == len(fake.calls) == 3


@pytest.mark.parametrize("tools", [[], [metadata("search_code")],
                                  [metadata("get_file_contents")]])
def test_missing_required_tool_degrades_and_closes(tools):
    fake = FakeClient(tools=tools)
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=fake.connect)
    assert not manager.start()
    registry = ToolRegistry()
    register_github_tools(registry, manager, JavaGitHubScopeResolver(Gateway()))
    assert not registry.definitions()
    assert fake.closed == 1
    assert not manager._thread.is_alive()


def test_disabled_never_creates_sdk_client():
    def forbidden(config):
        pytest.fail("disabled mode must not connect")
    manager = McpClientManager(GitHubMcpConfig(), client_factory=forbidden)
    assert not manager.start()
    manager.close()
    assert manager._thread is None


def test_metadata_schema_drift_is_rejected():
    bad = metadata("get_file_contents")
    bad.input_schema["required"].append("credential")
    with pytest.raises(GitHubMcpError, match="discovery_failed"):
        validate_catalog([metadata("search_code"), bad])


@pytest.mark.parametrize("query", ["repo:other/repo", "org:other", "user:other",
                                   "foo OR bar", "NOT foo", 'foo" repo:x/y',
                                   "repo：other/repo", "foo\nrepo:evil/x", "(foo)", ""])
def test_search_rejects_scope_escape_before_network(connected, query):
    manager, fake, gateway = connected
    tool = GitHubSearchCodeMcpTool(manager, JavaGitHubScopeResolver(gateway))
    with pytest.raises(InvalidToolArguments):
        tool.execute({"query": query}, run_context=CONTEXT, tool_call_id="call")
    assert not fake.calls and not gateway.scope_calls


def test_search_curates_fields_limit_and_binding(connected):
    manager, fake, gateway = connected
    data = GitHubSearchCodeMcpTool(manager, JavaGitHubScopeResolver(gateway)).execute(
        {"query": "DuplicateToolCallId", "path": "agent-service/src", "limit": 3},
        run_context=CONTEXT, tool_call_id="search")
    assert fake.calls == [("search_code", {
        "query": '"DuplicateToolCallId" repo:Obdeadsoup/DevPilot path:agent-service/src',
        "perPage": 3, "fields": FIELDS,
    })]
    assert data["source"] == "github_mcp" and data["untrusted"] is True
    assert PAT not in json.dumps(data)


@pytest.mark.parametrize("args", [{"query": "foo", "limit": 11},
                                  {"query": "foo", "limit": True},
                                  {"query": "foo", "owner": "evil"},
                                  {"query": "foo", "fields": ["repository"]},
                                  {"path": "README.md", "repo": "evil"},
                                  {"path": "README.md", "ref": "main"}])
def test_model_cannot_override_wire_policy(connected, args):
    manager, fake, gateway = connected
    cls = GitHubSearchCodeMcpTool if "query" in args else GitHubGetFileContentsMcpTool
    with pytest.raises(InvalidToolArguments):
        cls(manager, JavaGitHubScopeResolver(gateway)).execute(
            args, run_context=CONTEXT, tool_call_id="call")
    assert not fake.calls


@pytest.mark.parametrize("path", ["", "../secret", "/etc/passwd", "a/../b", "a\\b",
                                  "a//b", "a/%2e%2e/b", "a:repo", "a\nrepo:x/y"])
def test_path_validation(connected, path):
    manager, fake, gateway = connected
    with pytest.raises(InvalidToolArguments):
        GitHubGetFileContentsMcpTool(manager, JavaGitHubScopeResolver(gateway)).execute(
            {"path": path}, run_context=CONTEXT, tool_call_id="call")
    assert not fake.calls


def test_live_scope_every_call_revocation_and_branch_changes(connected):
    manager, fake, gateway = connected
    tool = GitHubGetFileContentsMcpTool(manager, JavaGitHubScopeResolver(gateway))
    tool.execute({"path": "README.md"}, run_context=CONTEXT, tool_call_id="one")
    gateway.branch = "feature/test"
    tool.execute({"path": "README.md"}, run_context=CONTEXT, tool_call_id="two")
    assert [args["ref"] for _, args in fake.calls] == ["agent", "feature/test"]
    gateway.allowed = False
    registry = ToolRegistry()
    registry.register(tool)
    with pytest.raises(ToolExecutionError):
        registry.execute(tool.name, {"path": "README.md"},
                         run_context=CONTEXT, tool_call_id="three")
    assert len(gateway.scope_calls) == 3 and len(fake.calls) == 2


def test_bounded_unicode_result_redaction_and_content_free_logging(connected, caplog):
    manager, fake, gateway = connected
    fake.responses = iter([
        result({"path": "README.md", "sha": "abc", "content": PAT + "汉"*10000})
    ])
    manager.config = GitHubMcpConfig(enabled=True, pat=PAT, max_result_bytes=1024)
    with caplog.at_level(logging.INFO):
        data = GitHubGetFileContentsMcpTool(manager, JavaGitHubScopeResolver(gateway)).execute(
            {"path": "README.md"}, run_context=CONTEXT, tool_call_id="one")
    assert len(json.dumps(data, ensure_ascii=False).encode()) <= 1024
    assert data["truncated"] and data["untrusted"]
    assert data["data"]["path"] == "README.md"
    assert PAT not in json.dumps(data) + caplog.text
    assert "汉" not in caplog.text
    assert "latency_ms=" in caplog.text and "result_bytes=" in caplog.text


@pytest.mark.parametrize("failure, count, kind", [
    (httpx2.ConnectError(PAT), 2, "network_failed"),
    (TimeoutError(PAT), 1, "timeout"),
    (ValueError(PAT), 1, "call_failed"),
    (MCPError(REQUEST_TIMEOUT, PAT), 1, "timeout"),
    (MCPError(-32602, PAT), 1, "protocol_error"),
])
def test_retry_classification_and_sanitized_exception(connected, failure, count, kind, caplog):
    manager, fake, _ = connected
    fake.responses = iter([failure, failure])
    with pytest.raises(GitHubMcpError) as caught:
        manager.call_tool("search_code", {"query": "foo", "perPage": 10})
    assert caught.value.kind == kind
    assert len(fake.calls) == count
    assert PAT not in str(caught.value) + caplog.text


def test_transient_retry_success_and_remote_error_not_retried(connected):
    manager, fake, _ = connected
    fake.responses = iter([httpx2.ConnectError(PAT), result({"content": "ok"}),
                           CallToolResult(content=[TextContent(type="text", text=PAT)],
                                          is_error=True)])
    manager.call_tool("search_code", {"query": "foo", "perPage": 10})
    with pytest.raises(GitHubMcpError, match="remote_tool_error"):
        manager.call_tool("search_code", {"query": "foo", "perPage": 10})
    assert len(fake.calls) == 3


def test_sdk_in_process_real_discovery_and_invocation():
    server = MCPServer("GitHub test server")
    calls = []

    @server.tool()
    def search_code(query: str, perPage: int = 10) -> str:
        calls.append(query)
        return "search data"

    @server.tool()
    def get_file_contents(owner: str, repo: str, path: str, ref: str) -> str:
        return "file data"

    @server.tool()
    def push_files() -> str:
        pytest.fail("write tool must never be called")

    @asynccontextmanager
    async def factory(config):
        async with Client(server, cache=None) as client:
            yield client

    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=factory)
    try:
        assert manager.start()
        assert set(manager.catalog) == {"search_code", "get_file_contents"}
        manager.call_tool("search_code", {"query": "foo repo:trusted/repo", "perPage": 3})
        assert calls == ["foo repo:trusted/repo"]
    finally:
        manager.close()
    assert not manager._thread.is_alive()


def workflow(connected, script, *, config=None, checkpoint=None):
    manager, _, gateway = connected
    registry = ToolRegistry()
    for cls in (
        ProjectSummaryTool, ListOpenTasksTool, RecentProjectActivityTool, KnowledgeSearchTool
    ):
        registry.register(cls(gateway))
    register_github_tools(registry, manager, JavaGitHubScopeResolver(gateway))
    planner = FakeModel([ModelResponse.final(json.dumps({
        "route": "ONLY_TOOL", "rewritten_query": "Find duplicate call guard in code",
        "confidence": 0.95, "reason_code": "LIVE_STATE",
    }))])
    model = FakeModel(script)
    flow = WorkflowRuntime(model, registry, lambda: FakeModel([]), planner_model=planner,
                           config=config, checkpointer=checkpoint,
                           redactor=RuntimeRedactor((PAT,)))
    return flow, model, registry


def search_call(call_id="search"):
    return ModelResponse.request_tools([ToolCall(call_id, "github.search_code",
                                               {"query": "DuplicateToolCallId"})])


def test_workflow_e2e_registry_messages_events_and_persistence(connected, tmp_path, caplog):
    checkpoint = GraphCheckpointStore(str(tmp_path / "graph.sqlite3"))
    flow, model, registry = workflow(connected, [
        search_call(), ModelResponse.request_tools([ToolCall("file", "github.get_file_contents",
                                                            {"path": "errors.py"})]),
        ModelResponse.final("found"), ModelResponse.final("answer"),
    ], checkpoint=checkpoint.saver)
    app = LangGraphRuntimeApplication(
        flow, SQLiteAgentRuntimeRepository(tmp_path / "run.sqlite3"), registry, None,
        close_callback=checkpoint.close,
    )
    try:
        answer = app.start_run("Where is the duplicate call guard?", run_context=CONTEXT)
        assert answer.final_answer == "answer"
        assert answer.tool_call_count == 2
        observations = [m for call in model.calls for m in call.messages
                        if m.role is MessageRole.TOOL]
        assert {m.tool_call_id for m in observations} == {"search", "file"}
        assert all(json.loads(m.content)["untrusted"] for m in observations)
        assert PAT not in json.dumps(answer.safe_trace()) + caplog.text
        routes = route_registries(flow.registry)
        assert set(MODEL_NAMES) <= {d.name for d in routes[PlannerRoute.ONLY_TOOL].definitions()}
        rag_names = {d.name for d in routes[PlannerRoute.ONLY_RAG].definitions()}
        assert not rag_names.intersection(MODEL_NAMES)
        assert all(d.name != "project.get_github_binding" for d in flow.registry.definitions())
    finally:
        app.close()
    assert all(PAT.encode() not in file.read_bytes()
               for file in tmp_path.iterdir() if file.is_file())


@pytest.mark.parametrize("script, config, error, count", [
    ([search_call("same"), search_call("same")], None, DuplicateToolCallIdError, 1),
    ([search_call(), search_call("second")], HarnessConfig(max_tool_calls=1),
     MaxToolCallsExceeded, 1),
])
def test_workflow_duplicate_and_budget_still_guard_mcp(connected, script, config, error, count):
    flow, _, _ = workflow(connected, script, config=config)
    with pytest.raises(error):
        flow.invoke("code question", run_context=CONTEXT)
    assert len(connected[1].calls) == count


@pytest.mark.parametrize("env", [{"DEVPILOT_GITHUB_MCP_ENABLED": "true"},
                                 {"DEVPILOT_GITHUB_MCP_PAT": PAT,
                                  "DEVPILOT_GITHUB_MCP_URL": "https://user:pass@example.com/"},
                                 {"DEVPILOT_GITHUB_MCP_TIMEOUT_SECONDS": PAT},
                                 {"DEVPILOT_GITHUB_MCP_TIMEOUT_SECONDS": "nan"}])
def test_config_rejects_invalid_values_without_echo(env):
    with pytest.raises(ValueError) as caught:
        GitHubMcpConfig.from_env(env)
    assert PAT not in str(caught.value)
    assert PAT not in repr(GitHubMcpConfig(enabled=True, pat=PAT))


def test_scope_requires_well_formed_authority():
    with pytest.raises(GitHubMcpError):
        TrustedGitHubScope("owner repo:evil/escape", "repo", "agent")


def test_remote_transport_headers_use_official_sdk(monkeypatch):
    # Inspect construction without contacting the remote server.
    captured = {}
    original = httpx2.AsyncClient

    def http_factory(**kwargs):
        captured.update(kwargs)
        return original(**kwargs)

    @asynccontextmanager
    async def fake_sdk(transport, **kwargs):
        captured["transport_module"] = type(transport).__module__
        yield FakeClient()

    monkeypatch.setattr("devpilot_agent_service.mcp.client.httpx2.AsyncClient", http_factory)
    monkeypatch.setattr("devpilot_agent_service.mcp.client.Client", fake_sdk)

    async def inspect_headers():
        async with github_sdk_client(GitHubMcpConfig(enabled=True, pat=PAT)):
            pass

    asyncio.run(inspect_headers())
    assert captured["headers"] == {"Authorization": f"Bearer {PAT}",
                                   "X-MCP-Tools": "search_code,get_file_contents",
                                   "X-MCP-Readonly": "true"}
    assert captured["follow_redirects"] is False


def test_inflight_timeout_cancels_request_and_keeps_lifecycle():
    cancelled = threading.Event()

    class SlowClient(FakeClient):
        async def call_tool(self, name, args):
            try:
                await asyncio.sleep(10)
            finally:
                cancelled.set()

    fake = SlowClient()
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT, timeout_seconds=0.1),
                               client_factory=fake.connect)
    try:
        assert manager.start()
        with pytest.raises(GitHubMcpError, match="timeout"):
            manager.call_tool("search_code", {"query": "foo", "perPage": 10})
        assert cancelled.wait(timeout=1)
        assert manager.available
    finally:
        manager.close()
    assert fake.closed == 1 and not manager._thread.is_alive()


def test_shutdown_cancels_inflight_worker_before_closing_sdk():
    entered = threading.Event()

    class SlowClient(FakeClient):
        async def call_tool(self, name, args):
            entered.set()
            await asyncio.sleep(10)

    fake = SlowClient()
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT, timeout_seconds=0.2),
                               client_factory=fake.connect)
    assert manager.start()
    with ThreadPoolExecutor(max_workers=1) as pool:
        call = pool.submit(manager.call_tool, "search_code", {"query": "foo", "perPage": 10})
        assert entered.wait(timeout=1)
        manager.close()
        with pytest.raises(GitHubMcpError):
            call.result(timeout=1)
    assert fake.closed == 1 and not manager._thread.is_alive()


def test_startup_connection_failure_is_sanitized(caplog):
    @asynccontextmanager
    async def broken(config):
        raise RuntimeError(PAT)
        yield  # pragma: no cover

    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=broken)
    assert not manager.start()
    assert PAT not in caplog.text
    assert not manager._thread.is_alive()


def test_paginated_discovery_walks_all_pages():
    class PaginatedClient(FakeClient):
        async def list_tools(self, cursor=None):
            self.lists += 1
            return SimpleNamespace(
                tools=[metadata("search_code" if cursor is None else "get_file_contents")],
                next_cursor="next" if cursor is None else None,
            )

    fake = PaginatedClient()
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=fake.connect)
    try:
        assert manager.start()
        assert fake.lists == 2
    finally:
        manager.close()


def test_production_assembly_registers_and_closes_optional_mcp(monkeypatch, tmp_path):
    from devpilot_agent_service.rpc.server import RpcServerConfig, create_application

    fake = FakeClient()
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=fake.connect)
    gateway = Gateway()
    monkeypatch.setenv("DEVPILOT_GITHUB_MCP_ENABLED", "true")
    monkeypatch.setenv("DEVPILOT_GITHUB_MCP_PAT", PAT)
    monkeypatch.setenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", "service-key-for-test-123")
    monkeypatch.setattr("devpilot_agent_service.rpc.server.McpClientManager", lambda cfg: manager)
    app = create_application(RpcServerConfig(model_mode="fake",
                                            runtime_db_path=str(tmp_path / "run.sqlite3")),
                             tool_client_factory=lambda cfg: gateway)
    try:
        names = {d.name for d in app._workflow.registry.definitions()}
        assert set(MODEL_NAMES) <= names
        assert "project.get_summary" in names
        assert "project.get_github_binding" not in names
    finally:
        app.close()
    assert fake.closed == 1


def test_production_degraded_mode_retains_native_tools(monkeypatch, tmp_path):
    from devpilot_agent_service.rpc.server import RpcServerConfig, create_application

    fake = FakeClient(tools=[])
    manager = McpClientManager(GitHubMcpConfig(enabled=True, pat=PAT), client_factory=fake.connect)
    monkeypatch.setenv("DEVPILOT_GITHUB_MCP_ENABLED", "true")
    monkeypatch.setenv("DEVPILOT_GITHUB_MCP_PAT", PAT)
    monkeypatch.setenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", "service-key-for-test-123")
    monkeypatch.setattr("devpilot_agent_service.rpc.server.McpClientManager", lambda cfg: manager)
    app = create_application(RpcServerConfig(model_mode="fake",
                                            runtime_db_path=str(tmp_path / "run.sqlite3")),
                             tool_client_factory=lambda cfg: Gateway())
    try:
        names = {d.name for d in app._workflow.registry.definitions()}
        assert not set(MODEL_NAMES).intersection(names)
        assert "project.get_summary" in names
    finally:
        app.close()


def test_provider_projection_uses_existing_alias_and_minimal_schema(connected):
    from devpilot_agent_service.model.providers.openai_compatible import to_provider_tool

    registry = ToolRegistry()
    register_github_tools(registry, connected[0], JavaGitHubScopeResolver(connected[2]))
    tool = to_provider_tool(registry.definitions()[0])
    assert tool["function"]["name"] == "github_search_code"
    assert set(tool["function"]["parameters"]["properties"]) == {"query", "path", "limit"}
    assert PAT not in json.dumps(tool)
