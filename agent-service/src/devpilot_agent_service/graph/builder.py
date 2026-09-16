"""Compile the isolated LangGraph parity skeleton."""

from langgraph.graph import END, START, StateGraph

from devpilot_agent_service.graph.nodes.agent import create_agent_node
from devpilot_agent_service.graph.nodes.finalize import finalize_node
from devpilot_agent_service.graph.nodes.tools import create_tool_node
from devpilot_agent_service.graph.routing import route_after_agent
from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.tools.registry import ToolRegistry


def build_agent_graph(model: Model, registry: ToolRegistry):
    """Build a non-durable graph without changing the production AgentLoop path."""

    graph = StateGraph(DevPilotAgentState)
    graph.add_node("agent", create_agent_node(model, registry))
    graph.add_node("tools", create_tool_node(registry))
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
