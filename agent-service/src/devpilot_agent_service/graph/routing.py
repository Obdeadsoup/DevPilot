"""Conditional routing decisions for the experimental graph."""

from typing import Literal

from langchain_core.messages import AIMessage

from devpilot_agent_service.graph.state import DevPilotAgentState


def route_after_agent(state: DevPilotAgentState) -> Literal["tools", "finalize"]:
    """Route structured tool calls to execution, otherwise finalize the answer."""

    messages = state["messages"]
    if not messages or not isinstance(messages[-1], AIMessage):
        raise ValueError("agent node must append an AIMessage before routing")
    return "tools" if messages[-1].tool_calls else "finalize"
