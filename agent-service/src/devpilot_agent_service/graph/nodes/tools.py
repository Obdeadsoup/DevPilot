"""Read-only tool execution node for the experimental graph."""

import json
from collections.abc import Callable

from langchain_core.messages import AIMessage, ToolMessage

from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.tools.base import ToolRisk
from devpilot_agent_service.tools.registry import ToolRegistry


def create_tool_node(
    registry: ToolRegistry,
) -> Callable[[DevPilotAgentState], dict[str, object]]:
    """Execute the latest model batch through ToolRegistry and preserve every call ID."""

    def tool_node(state: DevPilotAgentState) -> dict[str, object]:
        messages = state["messages"]
        if not messages or not isinstance(messages[-1], AIMessage):
            raise ValueError("tools node requires a preceding AIMessage")

        results: list[ToolMessage] = []
        for call in messages[-1].tool_calls:
            name = call["name"]
            if registry.risk(name) is not ToolRisk.READ_ONLY:
                raise ValueError(f"experimental graph only supports read-only tools: {name}")
            call_id = call.get("id")
            if not isinstance(call_id, str) or not call_id:
                raise ValueError("graph tool call must have a non-empty id")
            result = registry.execute(name, call["args"], tool_call_id=call_id)
            results.append(
                ToolMessage(
                    content=json.dumps(
                        result,
                        ensure_ascii=False,
                        sort_keys=True,
                        allow_nan=False,
                    ),
                    tool_call_id=call_id,
                    name=name,
                )
            )
        return {
            "messages": results,
            "tool_call_count": state.get("tool_call_count", 0) + len(results),
        }

    return tool_node
