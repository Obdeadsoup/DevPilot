"""Minimal working state for the experimental LangGraph path."""

from typing import Annotated, NotRequired, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages

from devpilot_agent_service.tools.planning import PlanItem


class DelegationRecord(TypedDict):
    agent: str
    tool_call_id: str
    model_call_count: int
    tool_call_count: int


class DevPilotAgentState(TypedDict):
    """Agent decision state, separate from durable Runtime Repository facts."""

    # add_messages merges message updates by ID and preserves ToolCall/ToolResult structure;
    # a plain list append cannot replace an existing message with the same ID.
    messages: Annotated[list[BaseMessage], add_messages]
    run_id: str | None
    request_id: NotRequired[str | None]
    tool_call_count: int
    model_call_count: NotRequired[int]
    plan: NotRequired[list[PlanItem]]
    context_summary: NotRequired[str | None]
    delegation_count: NotRequired[int]
    delegation_trace: NotRequired[list[DelegationRecord]]
    final_answer: str | None
    stop_reason: str | None
