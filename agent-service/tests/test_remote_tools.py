import json

import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import InvalidToolArguments
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.tools.devpilot import (
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.registry import ToolRegistry


class FakeGatewayClient:
    def __init__(self) -> None:
        self.calls: list[tuple[RunContext, str, str, dict[str, object]]] = []

    def execute(self, context, call_id, name, arguments):
        self.calls.append((context, call_id, name, dict(arguments)))
        return {"items": [{"key": "DP-1"}], "external_untrusted_content": True}


def test_remote_tool_definitions_and_argument_boundaries(repository) -> None:
    client = FakeGatewayClient()
    tools = [
        ProjectSummaryTool(client),
        ListOpenTasksTool(client),
        RecentProjectActivityTool(client),
    ]

    assert [tool.name for tool in tools] == [
        "project.get_summary",
        "task.list_open",
        "project.list_recent_activity",
    ]
    assert tools[1].parameter_schema["properties"]["limit"]["maximum"] == 20
    with pytest.raises(InvalidToolArguments):
        tools[0].execute({"scope": 1}, run_context=RunContext("run", "request"), tool_call_id="c")
    with pytest.raises(InvalidToolArguments):
        tools[1].execute({"limit": 21}, run_context=RunContext("run", "request"), tool_call_id="c")


def test_agent_loop_propagates_run_context_call_id_and_returns_tool_message(repository) -> None:
    client = FakeGatewayClient()
    registry = ToolRegistry()
    registry.register(ListOpenTasksTool(client))
    model = FakeModel(
        [
            ModelResponse.request_tools(
                [ToolCall("provider-call-1", "task.list_open", {"limit": 5})]
            ),
            ModelResponse.final("done"),
        ]
    )
    context = RunContext("run-1", "request-1")

    result = AgentLoop(model, registry, repository=repository).run(
        "list tasks", run_context=context
    )

    assert client.calls == [(context, "provider-call-1", "task.list_open", {"limit": 5})]
    tool_message = next(message for message in result.messages if message.role is MessageRole.TOOL)
    assert tool_message.tool_call_id == "provider-call-1"
    assert json.loads(tool_message.content)["external_untrusted_content"] is True
