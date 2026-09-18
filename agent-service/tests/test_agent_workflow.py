import json

import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import AIMessage, HumanMessage

from devpilot_agent_service.context import ContextBudget
from devpilot_agent_service.graph.workflow import WRITE_REQUIRES_LEGACY, route_registries
from devpilot_agent_service.harness.demo import DemoGatewayClient
from devpilot_agent_service.harness.runtime import HarnessConfig
from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.planner import PlannerRoute
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
    UnknownToolError,
)
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.tools.devpilot import (
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.registry import ToolRegistry

CONTEXT = RunContext("java-run", "java-request")


def make_workflow(route, script, *, planner_response=None, analyst=None, config=None, client=None):
    client = client or DemoGatewayClient()
    registry = ToolRegistry()
    for tool in (
        ProjectSummaryTool,
        ListOpenTasksTool,
        RecentProjectActivityTool,
        KnowledgeSearchTool,
    ):
        registry.register(tool(client))
    planner = FakeModel(
        [
            planner_response
            or ModelResponse.final(
                json.dumps(
                    {
                        "route": route,
                        "rewritten_query": "standalone rewritten query",
                        "confidence": 0.95,
                        "reason_code": "GENERAL",
                    }
                )
            )
        ]
    )
    model = FakeModel(script)
    workflow = WorkflowRuntime(
        model,
        registry,
        lambda: analyst or FakeModel([]),
        planner_model=planner,
        config=config,
    )
    return workflow, model, planner, client


@pytest.mark.parametrize(
    "route, query, script, expected",
    [
        ("DIRECT", "解释一下 CAS", [ModelResponse.final("CAS explanation")], []),
        (
            "ONLY_TOOL",
            "当前项目有哪些开放任务？",
            [
                ModelResponse.request_tools(
                    [ToolCall("provider-call-1", "task.list_open", {"limit": 5})]
                ),
                ModelResponse.final("collected live facts"),
                ModelResponse.final("live synthesis"),
            ],
            ["task.list_open"],
        ),
        (
            "ONLY_RAG",
            "架构文档为什么要求 Tool 幂等？",
            [ModelResponse.final("document synthesis")],
            ["knowledge.search"],
        ),
        (
            "HYBRID",
            "结合开放任务和架构文档分析当前风险",
            [
                ModelResponse.final("collected both"),
                ModelResponse.final("combined synthesis"),
            ],
            ["task.list_open", "knowledge.search"],
        ),
    ],
)
def test_four_routes_execute_complete_workflows_with_authoritative_context(
    route, query, script, expected
):
    workflow, model, planner, client = make_workflow(route, script)
    events = []
    result = workflow.invoke(query, run_context=CONTEXT, on_event=events.append)
    assert result.planner_decision["route"] == route
    assert [call[2] for call in client.calls] == expected
    assert all(call[0] == CONTEXT for call in client.calls)
    assert result.tool_call_count == len(expected)
    assert result.model_call_count == 1 + len(script)
    assert result.used_business_tool is ("task.list_open" in expected)
    assert result.used_rag is ("knowledge.search" in expected)
    assert model.calls[-1].tools == ()  # Explicit final synthesis cannot escape the route.
    observations = [m for m in model.calls[-1].messages if m.role is MessageRole.TOOL]
    assert {m.tool_name for m in observations} == set(expected)
    assert {m.tool_call_id for m in observations} == {call[1] for call in client.calls}
    if "knowledge.search" in expected:
        assert client.calls[-1][3]["query"] == "standalone rewritten query"
        assert "docs/architecture.md" in str(observations)
    if route == "ONLY_TOOL":
        assert client.calls[0][1] == "provider-call-1"
    assert planner.calls[0].tools == ()
    assert "standalone rewritten query" not in str(result.safe_trace())
    assert events


@pytest.mark.parametrize(
    "route, forbidden",
    [
        ("DIRECT", "task.list_open"),
        ("DIRECT", "knowledge.search"),
        ("ONLY_TOOL", "knowledge.search"),
        ("ONLY_TOOL", "delegate.project_analyst"),
        ("ONLY_RAG", "project.get_summary"),
        ("ONLY_RAG", "task.list_open"),
        ("ONLY_RAG", "delegate.project_analyst"),
    ],
)
def test_route_allowlists_are_code_enforced_against_adversarial_model_calls(route, forbidden):
    workflow, _, _, client = make_workflow(
        route, [ModelResponse.request_tools([ToolCall("evil-call", forbidden, {})])]
    )
    with pytest.raises(UnknownToolError):
        workflow.invoke("query", run_context=CONTEXT)
    assert [call[2] for call in client.calls] == (
        ["knowledge.search"] if route == "ONLY_RAG" else []
    )


def test_registry_snapshots_do_not_offer_document_capable_analyst_to_tool_only_route():
    workflow, _, _, _ = make_workflow("DIRECT", [])
    registries = route_registries(workflow.registry)
    assert not registries[PlannerRoute.DIRECT].definitions()
    assert {d.name for d in registries[PlannerRoute.ONLY_TOOL].definitions()} == {
        "project.get_summary",
        "task.list_open",
        "project.list_recent_activity",
        "plan.update",
    }
    assert {d.name for d in registries[PlannerRoute.ONLY_RAG].definitions()} == {"knowledge.search"}


@pytest.mark.parametrize(
    "response",
    [
        ModelResponse.final("invalid JSON"),
        ModelResponse.final(
            json.dumps(
                {
                    "route": "DIRECT",
                    "rewritten_query": "bad rewrite",
                    "confidence": 0.1,
                    "reason_code": "GENERAL",
                }
            )
        ),
        RuntimeError("provider-secret-body"),
    ],
)
def test_planner_fallback_really_executes_bounded_hybrid_not_just_classifies(response):
    workflow, model, _, client = make_workflow(
        "DIRECT",
        [
            ModelResponse.final("collected both"),
            ModelResponse.final("safe synthesis"),
        ],
        planner_response=response,
    )
    result = workflow.invoke("original query", run_context=CONTEXT)
    assert result.planner_decision["route"] == "HYBRID"
    assert [call[2] for call in client.calls] == ["task.list_open", "knowledge.search"]
    assert client.calls[-1][3]["query"] == "original query"
    assert "provider-secret-body" not in str(result)
    assert {m.tool_name for m in model.calls[-1].messages if m.role is MessageRole.TOOL} == {
        "task.list_open",
        "knowledge.search",
    }


@pytest.mark.parametrize("route", ["DIRECT", "ONLY_TOOL", "ONLY_RAG", "HYBRID"])
def test_write_request_requires_legacy_and_never_executes(route):
    workflow, _, _, client = make_workflow(
        route, [ModelResponse.request_tools([ToolCall("write", "task.create", {"title": "new"})])]
    )
    result = workflow.invoke("create task", run_context=CONTEXT)
    assert result.final_answer == WRITE_REQUIRES_LEGACY
    assert all(call[2] != "task.create" for call in client.calls)


def test_duplicate_provider_call_id_is_checked_against_original_history():
    repeated = ModelResponse.request_tools([ToolCall("repeat", "task.list_open", {})])
    workflow, _, _, client = make_workflow("ONLY_TOOL", [repeated, repeated])
    with pytest.raises(DuplicateToolCallIdError):
        workflow.invoke("open tasks", run_context=CONTEXT)
    assert len(client.calls) == 1


@pytest.mark.parametrize(
    "config, error",
    [
        (HarnessConfig(max_steps=1), MaxStepsExceeded),
        (HarnessConfig(max_tool_calls=1), MaxToolCallsExceeded),
    ],
)
def test_global_model_and_evidence_budgets_fail_before_remote_batch(config, error):
    workflow, _, _, client = make_workflow("HYBRID", [], config=config)
    with pytest.raises(error):
        workflow.invoke("hybrid query", run_context=CONTEXT)
    assert not client.calls


def test_hybrid_analyst_remains_isolated_and_returns_bounded_handoff():
    analyst = FakeModel(
        [
            ModelResponse.request_tools([ToolCall("child-task", "task.list_open", {})]),
            ModelResponse.final(
                json.dumps(
                    {
                        "summary": "isolated analysis",
                        "key_findings": ["finding"],
                        "sources": [],
                        "warnings": [],
                    }
                )
            ),
        ]
    )
    workflow, model, _, client = make_workflow(
        "HYBRID",
        [
            ModelResponse.request_tools(
                [ToolCall("delegate", "delegate.project_analyst", {"task": "standalone task"})]
            ),
            ModelResponse.final("handoff received"),
            ModelResponse.final("synthesized"),
        ],
        analyst=analyst,
    )
    result = workflow.invoke(
        "current question",
        run_context=CONTEXT,
        history=[
            HumanMessage(content="private unrelated history"),
            AIMessage(content="old answer"),
        ],
    )
    assert result.delegation_count == 1
    assert len(analyst.calls[0].messages) == 2
    assert "private unrelated history" not in str(analyst.calls)
    assert client.calls[-1][1].startswith("analyst:")
    assert client.calls[-1][0] == CONTEXT
    handoff = next(m for m in model.calls[-1].messages if m.tool_name == "delegate.project_analyst")
    assert len(handoff.content) <= 8000
    assert set(json.loads(handoff.content)) == {"summary", "key_findings", "sources", "warnings"}


def test_context_budget_limits_large_hybrid_evidence_at_actual_synthesis_input():
    class LargeGateway(DemoGatewayClient):
        def execute(self, *args):
            result = super().execute(*args)
            return {**result, "text": "x" * 100000}

    budget = ContextBudget(
        max_context_chars=10000, reserved_output_chars=1000, max_tool_result_chars=2000
    )
    workflow, model, _, _ = make_workflow(
        "HYBRID",
        [
            ModelResponse.final("ready"),
            ModelResponse.final("bounded synthesis"),
        ],
        config=HarnessConfig(context_budget=budget),
        client=LargeGateway(),
    )
    result = workflow.invoke("current question", run_context=CONTEXT)
    tool_messages = [m for m in model.calls[-1].messages if m.role is MessageRole.TOOL]
    assert len(tool_messages) == 2
    assert all(len(m.content) <= 2000 for m in tool_messages)
    assert result.context_summary is not None
    assert any(m.content == "current question" for m in model.calls[-1].messages)


def test_final_output_respects_java_utf16_length_and_redacts_credentials():
    workflow, _, _, _ = make_workflow(
        "DIRECT", [ModelResponse.final("sk-abcdefgh12345 " + "😀" * 70000)]
    )
    result = workflow.invoke("query", run_context=CONTEXT)
    assert "sk-abcdefgh12345" not in result.final_answer
    assert len(result.final_answer.encode("utf-16-le")) <= 130000
    assert result.safe_trace()["final_status"] == "SUCCEEDED"


def test_mixed_forbidden_batch_is_rejected_before_any_tool_executes():
    workflow, _, _, client = make_workflow(
        "ONLY_TOOL",
        [
            ModelResponse.request_tools(
                [
                    ToolCall("business", "task.list_open", {}),
                    ToolCall("forbidden", "knowledge.search", {"query": "docs"}),
                ]
            )
        ],
    )
    with pytest.raises(UnknownToolError):
        workflow.invoke("open tasks", run_context=CONTEXT)
    assert not client.calls
