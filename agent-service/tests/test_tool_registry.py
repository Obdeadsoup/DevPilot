from collections.abc import Mapping

import pytest

from devpilot_agent_service.runtime.errors import (
    DuplicateToolError,
    InvalidToolArguments,
    ToolExecutionError,
    UnknownToolError,
)
from devpilot_agent_service.tools.base import JsonValue
from devpilot_agent_service.tools.echo import EchoTool
from devpilot_agent_service.tools.registry import ToolRegistry


class ExplodingTool:
    name = "explode"
    description = "Always raises for error-boundary tests."
    parameter_schema: Mapping[str, object] = {"type": "object"}

    def execute(self, arguments: Mapping[str, object]) -> JsonValue:
        raise RuntimeError("local tool implementation failed")


def test_registry_registers_gets_lists_and_executes_tool() -> None:
    registry = ToolRegistry()
    tool = EchoTool()

    registry.register(tool)

    assert registry.get("echo") is tool
    assert [definition.name for definition in registry.definitions()] == ["echo"]
    assert registry.execute("echo", {"text": "hello"}) == {"echo": "hello"}


def test_registry_rejects_duplicate_name() -> None:
    registry = ToolRegistry()
    registry.register(EchoTool())

    with pytest.raises(DuplicateToolError, match="duplicate tool"):
        registry.register(EchoTool())


def test_registry_rejects_unknown_tool() -> None:
    with pytest.raises(UnknownToolError) as captured:
        ToolRegistry().execute("missing", {})

    assert captured.value.tool_name == "missing"


@pytest.mark.parametrize("arguments", [{}, {"text": 42}, {"text": "ok", "extra": True}])
def test_registry_preserves_invalid_argument_error(arguments: dict[str, object]) -> None:
    registry = ToolRegistry()
    registry.register(EchoTool())

    with pytest.raises(InvalidToolArguments):
        registry.execute("echo", arguments)


def test_registry_rejects_non_mapping_arguments() -> None:
    registry = ToolRegistry()
    registry.register(EchoTool())

    with pytest.raises(InvalidToolArguments, match="mapping"):
        registry.execute("echo", ["hello"])  # type: ignore[arg-type]


def test_registry_wraps_tool_exception_without_swallowing_cause() -> None:
    registry = ToolRegistry()
    registry.register(ExplodingTool())

    with pytest.raises(ToolExecutionError) as captured:
        registry.execute("explode", {})

    assert isinstance(captured.value.__cause__, RuntimeError)
