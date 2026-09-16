"""Final-answer projection node for the experimental graph."""

from langchain_core.messages import AIMessage

from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.runtime.errors import StopReason


def finalize_node(state: DevPilotAgentState) -> dict[str, str]:
    """Project the last final model message into explicit graph result fields."""

    messages = state["messages"]
    if not messages or not isinstance(messages[-1], AIMessage) or messages[-1].tool_calls:
        raise ValueError("finalize node requires a final AIMessage")
    if not isinstance(messages[-1].content, str):
        raise TypeError("experimental graph only supports string final answers")
    return {
        "final_answer": messages[-1].content,
        "stop_reason": StopReason.MODEL_FINAL.value,
    }
