"""Model node adapter for the experimental graph."""

from collections.abc import Callable, Sequence

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.graph.state import DevPilotAgentState
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.model.types import ModelResponse, ModelResponseKind, ToolCall
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.tools.registry import ToolRegistry


def create_agent_node(
    model: Model, registry: ToolRegistry
) -> Callable[[DevPilotAgentState], dict[str, list[AIMessage]]]:
    """Adapt graph messages to the existing provider-neutral Model abstraction."""

    def agent_node(state: DevPilotAgentState) -> dict[str, list[AIMessage]]:
        response = model.generate(_to_runtime_messages(state["messages"]), registry.definitions())
        if not isinstance(response, ModelResponse):
            raise TypeError("model must return ModelResponse")
        return {"messages": [_to_ai_message(response)]}

    return agent_node


def _to_ai_message(response: ModelResponse) -> AIMessage:
    if response.kind is ModelResponseKind.FINAL:
        return AIMessage(content=response.content)
    return AIMessage(
        content=response.content,
        tool_calls=[
            {
                "name": call.name,
                "args": dict(call.arguments),
                "id": call.call_id,
                "type": "tool_call",
            }
            for call in response.tool_calls
        ],
    )


def _to_runtime_messages(messages: Sequence[BaseMessage]) -> tuple[Message, ...]:
    converted: list[Message] = []
    tool_names: dict[str, str] = {}
    for message in messages:
        content = _string_content(message)
        if isinstance(message, SystemMessage):
            converted.append(Message.system(content))
        elif isinstance(message, HumanMessage):
            converted.append(Message.user(content))
        elif isinstance(message, AIMessage):
            calls = tuple(
                ToolCall(
                    call_id=_required_call_id(call.get("id")),
                    name=call["name"],
                    arguments=call["args"],
                )
                for call in message.tool_calls
            )
            if calls:
                converted.append(Message.assistant_tool_calls(calls, content=content))
                tool_names.update((call.call_id, call.name) for call in calls)
            else:
                converted.append(Message.assistant(content))
        elif isinstance(message, ToolMessage):
            call_id = message.tool_call_id
            tool_name = message.name or tool_names.get(call_id)
            if not tool_name:
                raise ValueError(f"tool result {call_id!r} has no matching tool name")
            converted.append(Message.tool_result(ToolCall(call_id, tool_name, {}), content))
        else:
            raise TypeError(f"unsupported graph message type: {type(message).__name__}")
    return tuple(converted)


def _required_call_id(call_id: object) -> str:
    if not isinstance(call_id, str) or not call_id:
        raise ValueError("graph tool call must have a non-empty id")
    return call_id


def _string_content(message: BaseMessage) -> str:
    if not isinstance(message.content, str):
        raise TypeError("experimental graph only supports string message content")
    return message.content
