import json

import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.agents.project_analyst.agent import ProjectAnalyst
from devpilot_agent_service.agents.registry import PROJECT_ANALYST_TOOLS, project_analyst_registry
from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.harness.demo import DemoGatewayClient
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import UnknownToolError
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.base import ToolRisk
from devpilot_agent_service.tools.devpilot import (
    CreateTaskTool,
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.planning import PlanningTool
from devpilot_agent_service.tools.registry import ToolRegistry


def complete_registry(client):
    registry = ToolRegistry()
    for tool_type in (
        ProjectSummaryTool,
        ListOpenTasksTool,
        RecentProjectActivityTool,
        KnowledgeSearchTool,
        CreateTaskTool,
    ):
        registry.register(tool_type(client))
    registry.register(PlanningTool())
    return registry


def test_subset_is_read_only_snapshot_and_does_not_leak_parent_capabilities():
    registry = complete_registry(DemoGatewayClient())
    child = project_analyst_registry(registry)
    assert [definition.name for definition in child.definitions()] == list(PROJECT_ANALYST_TOOLS)
    assert all(definition.risk is ToolRisk.READ_ONLY for definition in child.definitions())
    registry.register(
        type(
            "LaterTool",
            (),
            {
                "name": "later",
                "description": "Later capability",
                "parameter_schema": {},
            },
        )()
    )
    assert "later" not in [definition.name for definition in child.definitions()]
    with pytest.raises(ValueError, match="read-only"):
        registry.subset(["task.create"])
    with pytest.raises(UnknownToolError):
        registry.subset(["unknown"])


@pytest.mark.parametrize("forbidden", ["task.create", "delegate.project_analyst", "plan.update"])
def test_subagent_forbidden_calls_fail_in_code_even_if_model_ignores_prompt(forbidden):
    client = DemoGatewayClient()
    model = FakeModel([ModelResponse.request_tools([ToolCall("bad", forbidden, {})])])
    analyst = ProjectAnalyst(
        lambda: model, complete_registry(client), context_manager=ContextManager()
    )
    with pytest.raises(UnknownToolError):
        analyst.invoke("analyze", run_context=RunContext("run", "request"), delegation_id="d1")
    assert client.calls == []
    assert {definition.name for definition in model.calls[0].tools} == set(PROJECT_ANALYST_TOOLS)


@pytest.mark.parametrize("padding", ["s", "\\", "\u0000"])
def test_handoff_is_bounded_redacted_and_sources_are_grounded_in_observed_results(padding):
    source = "docs/architecture.md"
    model = FakeModel(
        [
            ModelResponse.request_tools([ToolCall("rag", "knowledge.search", {"query": "design"})]),
            ModelResponse.final(
                json.dumps(
                    {
                        "summary": "known-secret-value " + padding * 5000,
                        "key_findings": ["f" * 1000] * 20,
                        "sources": [source, "hallucinated.md"],
                        "warnings": ["w" * 1000] * 20,
                    }
                )
            ),
        ]
    )
    analyst = ProjectAnalyst(
        lambda: model,
        complete_registry(DemoGatewayClient()),
        context_manager=ContextManager(),
        redactor=RuntimeRedactor(("known-secret-value",)),
    )
    result = analyst.invoke("analyze", run_context=RunContext("run", "request"), delegation_id="d1")
    handoff = result.handoff
    assert len(handoff["summary"]) <= 2000
    assert len(handoff["key_findings"]) <= 5
    assert all(len(value) <= 400 for value in handoff["key_findings"])
    assert "known-secret-value" not in json.dumps(handoff)
    assert handoff["sources"] == [source]
    assert "unobserved source identifiers removed" in handoff["warnings"]
    assert len(json.dumps(handoff, ensure_ascii=False)) < 8000


@pytest.mark.parametrize("answer", ["private raw transcript", '{"messages": ["secret"]}', "[]"])
def test_invalid_structured_response_never_falls_back_to_returning_transcript(answer):
    model = FakeModel([ModelResponse.final(answer)])
    analyst = ProjectAnalyst(
        lambda: model, complete_registry(DemoGatewayClient()), context_manager=ContextManager()
    )
    result = analyst.invoke("task", run_context=RunContext("run", "request"), delegation_id="d1")
    assert "invalid analyst response" in result.handoff["warnings"][0]
    assert result.handoff["summary"] == (
        "Project Analyst did not return a valid structured analysis."
    )
    assert "messages" not in result.handoff
