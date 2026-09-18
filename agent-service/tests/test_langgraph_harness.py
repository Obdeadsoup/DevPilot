import json
import subprocess
import sys
from pathlib import Path

import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import AIMessage, HumanMessage

from devpilot_agent_service.agents.registry import PROJECT_ANALYST_TOOLS
from devpilot_agent_service.context import ContextBudget
from devpilot_agent_service.graph import build_agent_graph
from devpilot_agent_service.harness.demo import (
    DEMO_QUERY,
    DemoAnalystModel,
    DemoGatewayClient,
    DemoMainModel,
)
from devpilot_agent_service.harness.runtime import HarnessConfig, create_remote_harness
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidToolArguments,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
)
from devpilot_agent_service.tools.devpilot import KnowledgeSearchTool, ProjectSummaryTool
from devpilot_agent_service.tools.registry import ToolRegistry
from devpilot_agent_service.tools.subagents import DelegationBudgetExceeded

CONTEXT = RunContext("run-1", "request-1")


def request(call_id, name, args):
    return ModelResponse.request_tools([ToolCall(call_id, name, args)])


def initial():
    return {
        "messages": [HumanMessage(content="query")],
        "run_id": CONTEXT.run_id,
        "request_id": CONTEXT.request_id,
        "tool_call_count": 0,
        "final_answer": None,
        "stop_reason": None,
    }


@pytest.mark.parametrize(
    "tool_type,arguments",
    [
        (ProjectSummaryTool, {}),
        (KnowledgeSearchTool, {"query": "architecture", "topK": 3}),
    ],
)
def test_graph_remote_adapters_preserve_authoritative_context_and_call_id(tool_type, arguments):
    client = DemoGatewayClient()
    tool = tool_type(client)
    registry = ToolRegistry()
    registry.register(tool)
    model = FakeModel([request("provider-id", tool.name, arguments), ModelResponse.final("done")])
    result = build_agent_graph(model, registry).invoke(initial())
    assert client.calls == [(CONTEXT, "provider-id", tool.name, arguments)]
    assert model.calls[1].messages[-1].tool_call_id == "provider-id"
    assert json.loads(model.calls[1].messages[-1].content)["external_untrusted_content"] is True
    assert result["final_answer"] == "done"


def test_full_planning_delegation_rag_scenario_has_isolated_context_and_handoff():
    main = DemoMainModel()
    client = DemoGatewayClient()
    analysts = []

    def factory():
        model = DemoAnalystModel()
        analysts.append(model)
        return model

    runtime = create_remote_harness(main, client, factory)
    result = runtime.invoke(
        DEMO_QUERY,
        run_context=CONTEXT,
        history=[
            HumanMessage(content="UNRELATED_MAIN_HISTORY"),
            AIMessage(content="unrelated answer"),
        ],
    )
    assert result.delegation_count == 1
    assert result.model_call_count == 4
    assert result.tool_call_count == 3
    assert len(result.plan) == 4
    assert all(step["status"] == "DONE" for step in result.plan)
    assert [call[2] for call in client.calls] == list(PROJECT_ANALYST_TOOLS)
    assert all(call[0] == CONTEXT for call in client.calls)
    assert all(call[1].startswith("analyst:") for call in client.calls)
    assert len({call[1] for call in client.calls}) == 4
    assert len(analysts) == 1
    first_call = analysts[0].calls[0]
    assert len(first_call.messages) == 2
    assert "UNRELATED_MAIN_HISTORY" not in str(first_call.messages)
    assert {tool.name for tool in first_call.tools} == set(PROJECT_ANALYST_TOOLS)
    assert {tool.name for tool in main.calls[0].tools} == {
        *PROJECT_ANALYST_TOOLS,
        "plan.update",
        "delegate.project_analyst",
    }
    assert "external untrusted data" in first_call.messages[0].content
    assert "external untrusted data" in main.calls[0].messages[0].content
    handoff = json.loads(main.calls[2].messages[-1].content)
    assert set(handoff) == {"summary", "key_findings", "sources", "warnings"}
    assert handoff["sources"] == ["docs/architecture.md"]
    assert "messages" not in handoff
    assert "docs/architecture.md" in result.final_answer
    assert result.delegation_trace[0]["tool_call_count"] == 4


def test_simple_query_does_not_require_plan_or_delegation():
    client = DemoGatewayClient()
    main = FakeModel(
        [request("summary", "project.get_summary", {}), ModelResponse.final("DevPilot")]
    )
    runtime = create_remote_harness(main, client, lambda: pytest.fail("unexpected delegation"))
    result = runtime.invoke("项目叫什么？", run_context=CONTEXT)
    assert result.plan == [] and result.delegation_count == 0
    assert result.final_answer == "DevPilot"


def test_remote_tools_cannot_take_identity_from_model_arguments():
    client = DemoGatewayClient()
    model = FakeModel([request("c1", "project.get_summary", {"run_id": "forged"})])
    runtime = create_remote_harness(model, client, DemoAnalystModel)
    with pytest.raises(InvalidToolArguments):
        runtime.invoke("query", run_context=CONTEXT)
    assert client.calls == []


def test_remote_calls_without_context_fail_closed():
    client = DemoGatewayClient()
    model = FakeModel([request("c1", "project.get_summary", {})])
    runtime = create_remote_harness(model, client, DemoAnalystModel)
    with pytest.raises(InvalidToolArguments):
        runtime.invoke("query")
    assert client.calls == []


@pytest.mark.parametrize("batch", [False, True])
def test_delegation_budget_rejects_third_call_before_starting_another_subgraph(batch):
    responses = [
        request("d1", "delegate.project_analyst", {"task": "read one"}),
        request("d2", "delegate.project_analyst", {"task": "read two"}),
        request("d3", "delegate.project_analyst", {"task": "read three"}),
    ]
    main = FakeModel(
        [
            ModelResponse.request_tools(
                [call for response in responses for call in response.tool_calls]
            )
        ]
        if batch
        else responses
    )
    analysts = []

    def factory():
        model = DemoAnalystModel()
        analysts.append(model)
        return model

    client = DemoGatewayClient()
    runtime = create_remote_harness(main, client, factory)
    with pytest.raises(DelegationBudgetExceeded):
        runtime.invoke("analyze", run_context=CONTEXT)
    assert len(analysts) == 2
    assert len(client.calls) == 8
    assert len({call[1] for call in client.calls}) == 8


def test_invocation_workflow_state_does_not_leak_between_runs():
    runtime = create_remote_harness(DemoMainModel(), DemoGatewayClient(), DemoAnalystModel)
    first = runtime.invoke(DEMO_QUERY, run_context=CONTEXT)
    second = runtime.invoke(DEMO_QUERY, run_context=RunContext("run-2", "request-2"))
    assert first.delegation_count == second.delegation_count == 1
    assert first.tool_call_count == second.tool_call_count == 3
    assert len(first.delegation_trace) == len(second.delegation_trace) == 1


def test_main_and_subagent_model_step_limits_are_enforced():
    runtime = create_remote_harness(
        DemoMainModel(),
        DemoGatewayClient(),
        DemoAnalystModel,
        config=HarnessConfig(subagent_max_steps=1),
    )
    with pytest.raises(MaxStepsExceeded):
        runtime.invoke(DEMO_QUERY, run_context=CONTEXT)
    runtime = create_remote_harness(
        DemoMainModel(),
        DemoGatewayClient(),
        DemoAnalystModel,
        config=HarnessConfig(max_steps=1),
    )
    with pytest.raises(MaxStepsExceeded):
        runtime.invoke(DEMO_QUERY, run_context=CONTEXT)


def test_tool_budget_rejects_whole_batch_without_gateway_execution():
    client = DemoGatewayClient()
    model = FakeModel(
        [
            ModelResponse.request_tools(
                [
                    ToolCall("c1", "project.get_summary", {}),
                    ToolCall("c2", "project.get_summary", {}),
                ]
            )
        ]
    )
    runtime = create_remote_harness(
        model, client, DemoAnalystModel, config=HarnessConfig(max_tool_calls=1)
    )
    with pytest.raises(MaxToolCallsExceeded):
        runtime.invoke("query", run_context=CONTEXT)
    assert client.calls == []


def test_subagent_tool_budget_rejects_batch_without_gateway_execution():
    client = DemoGatewayClient()
    runtime = create_remote_harness(
        DemoMainModel(),
        client,
        DemoAnalystModel,
        config=HarnessConfig(subagent_max_tool_calls=3),
    )
    with pytest.raises(MaxToolCallsExceeded):
        runtime.invoke(DEMO_QUERY, run_context=CONTEXT)
    assert client.calls == []


@pytest.mark.parametrize(
    "arguments",
    [
        {"task": ""},
        {"task": "x" * 2001},
        {"task": []},
        {"task": "analyze", "run_id": "forged"},
    ],
)
def test_delegation_schema_is_bounded_and_does_not_accept_model_identity(arguments):
    runtime = create_remote_harness(
        FakeModel([request("d1", "delegate.project_analyst", arguments)]),
        DemoGatewayClient(),
        lambda: pytest.fail("invalid task started subagent"),
    )
    with pytest.raises(InvalidToolArguments):
        runtime.invoke("query", run_context=CONTEXT)


def test_duplicate_id_cannot_repeat_remote_execution():
    client = DemoGatewayClient()
    model = FakeModel(
        [
            request("same", "project.get_summary", {}),
            request("same", "project.get_summary", {}),
        ]
    )
    runtime = create_remote_harness(model, client, DemoAnalystModel)
    with pytest.raises(DuplicateToolCallIdError):
        runtime.invoke("query", run_context=CONTEXT)
    assert len(client.calls) == 1


def test_subagent_oversized_observations_use_context_manager():
    class LargeClient(DemoGatewayClient):
        def execute(self, *args):
            return {**super().execute(*args), "long_text": "x" * 100_000}

    analysts = []

    def factory():
        analyst = DemoAnalystModel()
        analysts.append(analyst)
        return analyst

    runtime = create_remote_harness(
        DemoMainModel(),
        LargeClient(),
        factory,
        config=HarnessConfig(context_budget=ContextBudget(12000, 1000, 20, 2000)),
    )
    result = runtime.invoke(DEMO_QUERY, run_context=CONTEXT)
    actual = analysts[0].calls[1].messages
    assert len(actual) == 7
    assert all(len(message.content) <= 1000 for message in actual if message.tool_name)
    assert sum(len(message.content) for message in actual) < 10000
    assert result.delegation_count == 1


def test_cli_fake_runs_offline_and_real_requires_run_bound_identity():
    script = Path(__file__).resolve().parents[1] / "examples/langgraph_harness_demo.py"
    fake = subprocess.run(
        [sys.executable, str(script)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
        timeout=30,
    )
    payload = json.loads(fake.stdout)
    assert payload["mode"] == "fake"
    assert payload["delegation_count"] == 1
    assert payload["gateway_tool_order"] == list(PROJECT_ANALYST_TOOLS)
    real = subprocess.run(
        [sys.executable, str(script), "--mode", "real"], capture_output=True, text=True, timeout=30
    )
    assert real.returncode == 2
    assert "active Java run" in real.stderr
