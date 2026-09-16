"""Minimal working state for the experimental LangGraph path."""

from typing import Annotated, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class DevPilotAgentState(TypedDict):
    """Agent decision state, separate from durable Runtime Repository facts."""

    # add_messages merges message updates by ID and preserves ToolCall/ToolResult structure;
    # a plain list append cannot replace an existing message with the same ID.
    messages: Annotated[list[BaseMessage], add_messages]
    run_id: str | None
    tool_call_count: int
    final_answer: str | None
    stop_reason: str | None
