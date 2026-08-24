from collections.abc import Mapping

import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidToolArguments,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
    ModelInvocationError,
    StopReason,
    ToolExecutionError,
    UnknownToolError,
)
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.tools.base import JsonValue
from devpilot_agent_service.tools.echo import EchoTool
from devpilot_agent_service.tools.registry import ToolRegistry


class ExplodingTool:
    name = "explode"
    description = "Always raises for AgentLoop tests."
    parameter_schema: Mapping[str, object] = {"type": "object"}

    def execute(self, arguments: Mapping[str, object]) -> JsonValue:
        raise RuntimeError("boom")


class CountingTool:
    name = "count"
    description = "Records executions for AgentLoop budget tests."
    parameter_schema: Mapping[str, object] = {"type": "object"}

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def execute(self, arguments: Mapping[str, object]) -> JsonValue:
        self.calls.append(dict(arguments))
        return {"count": len(self.calls)}


def tool_response(call_id: str, name: str = "echo", **arguments: object) -> ModelResponse:
    return ModelResponse.request_tools(
        [ToolCall(call_id=call_id, name=name, arguments=arguments)]
    )


def tools_response(*calls: ToolCall) -> ModelResponse:
    return ModelResponse.request_tools(calls)


def echo_registry() -> ToolRegistry:
    registry = ToolRegistry()
    registry.register(EchoTool())
    return registry


def counting_registry(tool: CountingTool) -> ToolRegistry:
    registry = ToolRegistry()
    registry.register(tool)
    return registry


def test_direct_final_stops_without_tool() -> None:
    model = FakeModel([ModelResponse.final("done")])

    result = AgentLoop(model, echo_registry()).run("hello")

    assert result.final_answer == "done"
    assert result.stop_reason is StopReason.MODEL_FINAL
    assert [message.role for message in result.messages] == [
        MessageRole.USER,
        MessageRole.ASSISTANT,
    ]
    assert result.trace[-1].stop_reason is StopReason.MODEL_FINAL


def test_one_tool_call_returns_result_to_model_then_stops() -> None:
    model = FakeModel(
        [
            tool_response("call-1", text="hello"),
            ModelResponse.final("echo complete"),
        ]
    )

    result = AgentLoop(model, echo_registry()).run("please echo")

    assert result.final_answer == "echo complete"
    assert len(model.calls) == 2
    second_messages = model.calls[1].messages
    assert [message.role for message in second_messages] == [
        MessageRole.USER,
        MessageRole.ASSISTANT,
        MessageRole.TOOL,
    ]
    assert second_messages[-1].content == '{"echo": "hello"}'
    assert second_messages[-1].tool_call_id == "call-1"
    assert model.calls[0].tools[0].name == "echo"


def test_multiple_tool_rounds_accumulate_results_in_context() -> None:
    model = FakeModel(
        [
            tool_response("call-1", text="first"),
            tool_response("call-2", text="second"),
            ModelResponse.final("done"),
        ]
    )

    result = AgentLoop(model, echo_registry()).run("two rounds")

    assert result.final_answer == "done"
    tool_messages = [
        message for message in model.calls[2].messages if message.role is MessageRole.TOOL
    ]
    assert [message.content for message in tool_messages] == [
        '{"echo": "first"}',
        '{"echo": "second"}',
    ]
    assert [step.tool_names for step in result.trace] == [("echo",), ("echo",), ()]


def test_unknown_tool_has_explicit_invalid_tool_call_stop_reason() -> None:
    model = FakeModel([tool_response("call-1", name="missing")])

    with pytest.raises(UnknownToolError) as captured:
        AgentLoop(model, echo_registry()).run("unknown")

    assert captured.value.stop_reason is StopReason.INVALID_TOOL_CALL


def test_invalid_tool_arguments_have_explicit_stop_reason() -> None:
    model = FakeModel([tool_response("call-1")])

    with pytest.raises(InvalidToolArguments) as captured:
        AgentLoop(model, echo_registry()).run("invalid arguments")

    assert captured.value.stop_reason is StopReason.INVALID_TOOL_CALL


def test_tool_exception_has_explicit_tool_error_stop_reason() -> None:
    registry = ToolRegistry()
    registry.register(ExplodingTool())
    model = FakeModel([tool_response("call-1", name="explode")])

    with pytest.raises(ToolExecutionError) as captured:
        AgentLoop(model, registry).run("explode")

    assert captured.value.stop_reason is StopReason.TOOL_ERROR


def test_model_exception_has_explicit_model_error_stop_reason() -> None:
    model = FakeModel([RuntimeError("provider unavailable")])

    with pytest.raises(ModelInvocationError) as captured:
        AgentLoop(model, echo_registry()).run("hello")

    assert captured.value.stop_reason is StopReason.MODEL_ERROR
    assert captured.value.provider_kind is ProviderErrorKind.UNKNOWN
    assert isinstance(captured.value.__cause__, RuntimeError)


def test_provider_error_kind_survives_runtime_boundary() -> None:
    provider_error = ProviderError(ProviderErrorKind.RATE_LIMIT)
    model = FakeModel([provider_error])

    with pytest.raises(ModelInvocationError) as captured:
        AgentLoop(model, echo_registry()).run("hello")

    assert captured.value.provider_kind is ProviderErrorKind.RATE_LIMIT
    assert captured.value.__cause__ is provider_error


def test_max_steps_stops_model_that_keeps_requesting_tools() -> None:
    model = FakeModel(
        [
            tool_response("call-1", text="first"),
            tool_response("call-2", text="second"),
            tool_response("call-3", text="must not run"),
        ]
    )

    with pytest.raises(MaxStepsExceeded) as captured:
        AgentLoop(model, echo_registry(), max_steps=2).run("keep going")

    assert captured.value.stop_reason is StopReason.MAX_STEPS
    assert len(model.calls) == 2


def test_max_steps_must_be_positive_integer() -> None:
    with pytest.raises(ValueError, match="正整数"):
        AgentLoop(FakeModel([]), echo_registry(), max_steps=0)


def test_max_tool_calls_must_be_positive_integer() -> None:
    with pytest.raises(ValueError, match="正整数"):
        AgentLoop(FakeModel([]), echo_registry(), max_tool_calls=0)


def test_multiple_calls_in_one_response_execute_within_budget() -> None:
    tool = CountingTool()
    model = FakeModel(
        [
            tools_response(
                ToolCall("call-1", "count", {"value": 1}),
                ToolCall("call-2", "count", {"value": 2}),
            ),
            ModelResponse.final("done"),
        ]
    )

    result = AgentLoop(
        model,
        counting_registry(tool),
        max_tool_calls=2,
    ).run("count twice")

    assert result.final_answer == "done"
    assert tool.calls == [{"value": 1}, {"value": 2}]


def test_over_budget_batch_executes_no_tool() -> None:
    tool = CountingTool()
    model = FakeModel(
        [
            tools_response(
                ToolCall("call-1", "count", {}),
                ToolCall("call-2", "count", {}),
            )
        ]
    )

    with pytest.raises(MaxToolCallsExceeded) as captured:
        AgentLoop(
            model,
            counting_registry(tool),
            max_tool_calls=1,
        ).run("too many")

    assert captured.value.stop_reason is StopReason.MAX_TOOL_CALLS
    assert tool.calls == []


def test_tool_call_budget_is_cumulative_across_steps() -> None:
    tool = CountingTool()
    model = FakeModel(
        [
            tools_response(ToolCall("call-1", "count", {})),
            tools_response(
                ToolCall("call-2", "count", {}),
                ToolCall("call-3", "count", {}),
            ),
        ]
    )

    with pytest.raises(MaxToolCallsExceeded):
        AgentLoop(
            model,
            counting_registry(tool),
            max_tool_calls=2,
        ).run("three calls")

    assert tool.calls == [{}]


def test_duplicate_tool_call_id_across_steps_is_rejected_before_second_execution() -> None:
    tool = CountingTool()
    model = FakeModel(
        [
            tools_response(ToolCall("same-id", "count", {"round": 1})),
            tools_response(ToolCall("same-id", "count", {"round": 2})),
        ]
    )

    with pytest.raises(DuplicateToolCallIdError) as captured:
        AgentLoop(model, counting_registry(tool)).run("duplicate")

    assert captured.value.stop_reason is StopReason.INVALID_TOOL_CALL
    assert tool.calls == [{"round": 1}]


def test_duplicate_tool_call_ids_in_one_batch_execute_no_tool() -> None:
    tool = CountingTool()
    model = FakeModel(
        [
            tools_response(
                ToolCall("same-id", "count", {"position": 1}),
                ToolCall("same-id", "count", {"position": 2}),
            )
        ]
    )

    with pytest.raises(DuplicateToolCallIdError):
        AgentLoop(model, counting_registry(tool)).run("duplicate batch")

    assert tool.calls == []
