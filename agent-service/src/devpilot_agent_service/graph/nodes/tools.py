"""Read-only tool execution node for the experimental graph."""

import json
from collections.abc import Callable
from hashlib import sha256

from langchain_core.messages import AIMessage, ToolMessage

from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import MaxToolCallsExceeded
from devpilot_agent_service.runtime.events import RuntimeEvent, RuntimeEventType
from devpilot_agent_service.tools.base import ToolRisk
from devpilot_agent_service.tools.registry import ToolRegistry
from devpilot_agent_service.tools.workflow import WorkflowTool


def create_tool_node(
    registry: ToolRegistry,
    *,
    max_tool_calls: int = 16,
    tool_call_namespace: str | None = None,
    on_event: Callable[[RuntimeEvent], None] | None = None,
) -> Callable[[DevPilotAgentState], dict[str, object]]:
    """Execute the latest model batch through ToolRegistry and preserve every call ID."""

    def tool_node(state: DevPilotAgentState) -> dict[str, object]:
        messages = state["messages"]
        if not messages or not isinstance(messages[-1], AIMessage):
            raise ValueError("tools node requires a preceding AIMessage")

        calls = messages[-1].tool_calls
        if state.get("tool_call_count", 0) + len(calls) > max_tool_calls:
            raise MaxToolCallsExceeded(max_tool_calls)
        run_id, request_id = state.get("run_id"), state.get("request_id")
        if (run_id is None) != (request_id is None):
            # Old local skeleton inputs may have run_id only, but it is never sufficient for RPC.
            raise ValueError("remote tool context requires both run_id and request_id")
        run_context = RunContext(run_id, request_id) if run_id is not None else None
        # Check the entire batch before any tool executes; never partially run a write batch.
        for call in calls:
            if registry.risk(call["name"]) is not ToolRisk.READ_ONLY:
                raise ValueError("experimental graph only supports read-only tools; use legacy")
        results: list[ToolMessage] = []
        working = dict(state)
        updates: dict[str, object] = {}
        for call in calls:
            name = call["name"]
            if registry.risk(name) is not ToolRisk.READ_ONLY:
                raise ValueError(f"experimental graph only supports read-only tools: {name}")
            call_id = call.get("id")
            if not isinstance(call_id, str) or not call_id:
                raise ValueError("graph tool call must have a non-empty id")
            tool = registry.get(name)
            step = max(1, state.get("model_call_count", 0))
            if on_event:
                on_event(RuntimeEvent(RuntimeEventType.TOOL_STARTED, step, name))
            if isinstance(tool, WorkflowTool):
                workflow = tool.execute_in_graph(
                    call["args"],
                    state=working,
                    run_context=run_context,
                    tool_call_id=call_id,
                )
                if set(workflow.update) - {"plan", "delegation_count", "delegation_trace"}:
                    raise ValueError("workflow tools cannot modify execution identity or policy")
                result = workflow.result
                working.update(workflow.update)
                updates.update(workflow.update)
            else:
                # Independent child invocations share a Java run, so namespace their RPC IDs.
                # Local ToolMessages retain the original provider ID for model protocol parity.
                gateway_call_id = (
                    tool_call_namespace + ":" + sha256(call_id.encode()).hexdigest()[:24]
                    if tool_call_namespace
                    else call_id
                )
                result = registry.execute(
                    name,
                    call["args"],
                    run_context=run_context,
                    tool_call_id=gateway_call_id,
                )
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
            if on_event:
                on_event(RuntimeEvent(RuntimeEventType.TOOL_COMPLETED, step, name))
        return {
            **updates,
            "messages": results,
            "tool_call_count": state.get("tool_call_count", 0) + len(results),
        }

    return tool_node
