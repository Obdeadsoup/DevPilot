from collections.abc import Mapping

import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import HumanMessage, ToolMessage

from devpilot_agent_service.graph import build_agent_graph
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.tools.base import JsonValue, ToolRisk
from devpilot_agent_service.tools.echo import EchoTool
from devpilot_agent_service.tools.registry import ToolRegistry


class RecordingTool:
    name = "record"
    description = "Record read-only calls for graph tests."
    parameter_schema: Mapping[str, object] = {"type": "object"}

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def execute(self, arguments: Mapping[str, object]) -> JsonValue:
        copied = dict(arguments)
        self.calls.append(copied)
        return {"seen": copied}


class WriteTool(RecordingTool):
    name = "write"
    risk = ToolRisk.WRITE_REQUIRES_APPROVAL


def registry_with(*tools: object) -> ToolRegistry:
    registry = ToolRegistry()
    for tool in tools:
        registry.register(tool)
    return registry


def initial_state(user_input: str) -> dict[str, object]:
    return {
        "messages": [HumanMessage(content=user_input)],
        "run_id": None,
        "tool_call_count": 0,
        "final_answer": None,
        "stop_reason": None,
    }


def test_graph_model_final_reaches_finalize_without_tools() -> None:
    model = FakeModel([ModelResponse.final("done")])
    tool = RecordingTool()

    result = build_agent_graph(model, registry_with(tool)).invoke(initial_state("hello"))

    assert len(model.calls) == 1
    assert tool.calls == []
    assert result["final_answer"] == "done"
    assert result["stop_reason"] == "model_final"
    assert result["tool_call_count"] == 0


def test_graph_executes_one_read_tool_and_returns_result_to_model() -> None:
    model = FakeModel(
        [
            ModelResponse.request_tools([ToolCall("call-1", "echo", {"text": "hello"})]),
            ModelResponse.final("echo complete"),
        ]
    )

    result = build_agent_graph(model, registry_with(EchoTool())).invoke(
        initial_state("please echo")
    )

    assert len(model.calls) == 2
    tool_result = model.calls[1].messages[-1]
    assert tool_result.tool_call_id == "call-1"
    assert tool_result.content == '{"echo": "hello"}'
    assert result["final_answer"] == "echo complete"
    assert result["tool_call_count"] == 1


def test_graph_preserves_multiple_tool_call_ids_and_results() -> None:
    tool = RecordingTool()
    model = FakeModel(
        [
            ModelResponse.request_tools(
                [
                    ToolCall("call-1", "record", {"value": 1}),
                    ToolCall("call-2", "record", {"value": 2}),
                ]
            ),
            ModelResponse.final("recorded"),
        ]
    )

    result = build_agent_graph(model, registry_with(tool)).invoke(initial_state("record twice"))

    assert tool.calls == [{"value": 1}, {"value": 2}]
    graph_tool_messages = [
        message for message in result["messages"] if isinstance(message, ToolMessage)
    ]
    assert [message.tool_call_id for message in graph_tool_messages] == ["call-1", "call-2"]
    assert [message.tool_call_id for message in model.calls[1].messages[-2:]] == [
        "call-1",
        "call-2",
    ]
    assert result["tool_call_count"] == 2
    assert result["final_answer"] == "recorded"


def test_supported_read_tool_scenario_matches_legacy_observables(repository) -> None:
    script = [
        ModelResponse.request_tools([ToolCall("call-1", "echo", {"text": "same"})]),
        ModelResponse.final("same answer"),
    ]
    legacy_model = FakeModel(script)
    graph_model = FakeModel(script)

    legacy = AgentLoop(
        legacy_model,
        registry_with(EchoTool()),
        repository=repository,
    ).run("compare")
    graph = build_agent_graph(graph_model, registry_with(EchoTool())).invoke(
        initial_state("compare")
    )

    assert len(legacy_model.calls) == len(graph_model.calls) == 2
    assert legacy_model.calls[1].messages[-1].tool_name == "echo"
    assert graph_model.calls[1].messages[-1].tool_name == "echo"
    assert legacy_model.calls[1].messages[-1].tool_call_id == "call-1"
    assert graph_model.calls[1].messages[-1].tool_call_id == "call-1"
    assert legacy.final_answer == graph["final_answer"] == "same answer"


def test_graph_never_executes_write_tools() -> None:
    tool = WriteTool()
    model = FakeModel(
        [ModelResponse.request_tools([ToolCall("write-1", "write", {"value": 1})])]
    )

    with pytest.raises(ValueError, match="only supports read-only tools"):
        build_agent_graph(model, registry_with(tool)).invoke(initial_state("write"))

    assert tool.calls == []
