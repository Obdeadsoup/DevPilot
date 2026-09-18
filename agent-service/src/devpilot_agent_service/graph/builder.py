"""Compile read-oriented orchestration without replacing the durable runtime."""

from langgraph.graph import END, START, StateGraph

from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.graph.nodes.agent import create_agent_node
from devpilot_agent_service.graph.nodes.finalize import finalize_node
from devpilot_agent_service.graph.nodes.tools import create_tool_node
from devpilot_agent_service.graph.routing import route_after_agent
from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.tools.registry import ToolRegistry


def build_agent_graph(
    model: Model,
    registry: ToolRegistry,
    *,
    context_manager: ContextManager | None = None,
    max_steps: int = 8,
    max_tool_calls: int = 16,
    tool_call_namespace: str | None = None,
):
    """Build a non-durable graph without changing the production AgentLoop path."""

    if any(type(value) is not int or value < 1 for value in (max_steps, max_tool_calls)):
        raise ValueError("graph execution budgets must be positive integers")
    graph = StateGraph(DevPilotAgentState)
    graph.add_node(
        "agent",
        create_agent_node(
            model,
            registry,
            context_manager=context_manager or ContextManager(),
            max_steps=max_steps,
            max_tool_calls=max_tool_calls,
        ),
    )
    graph.add_node(
        "tools",
        create_tool_node(
            registry,
            max_tool_calls=max_tool_calls,
            tool_call_namespace=tool_call_namespace,
        ),
    )
    graph.add_node("finalize", finalize_node)
    graph.add_edge(START, "agent")
    graph.add_conditional_edges(
        "agent",
        route_after_agent,
        {"tools": "tools", "finalize": "finalize"},
    )
    graph.add_edge("tools", "agent")
    graph.add_edge("finalize", END)
    return graph.compile()
