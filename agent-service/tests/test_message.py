import pytest

from devpilot_agent_service.model.types import (
    ModelResponse,
    ModelResponseKind,
    ToolCall,
)
from devpilot_agent_service.runtime.message import Message, MessageRole


@pytest.mark.parametrize(
    ("factory", "role"),
    [
        (Message.system, MessageRole.SYSTEM),
        (Message.user, MessageRole.USER),
        (Message.assistant, MessageRole.ASSISTANT),
    ],
)
def test_message_accepts_supported_roles(factory: object, role: MessageRole) -> None:
    message = factory("hello")  # type: ignore[operator]

    assert message.role is role
    assert message.content == "hello"


def test_message_rejects_raw_or_unknown_role() -> None:
    with pytest.raises(TypeError, match="MessageRole"):
        Message(role="developer", content="hello")  # type: ignore[arg-type]


def test_tool_message_requires_call_id_and_name() -> None:
    with pytest.raises(ValueError, match="tool_call_id"):
        Message(role=MessageRole.TOOL, content="result")


def test_non_tool_message_rejects_tool_result_fields() -> None:
    with pytest.raises(ValueError, match="非 tool message"):
        Message(
            role=MessageRole.USER,
            content="hello",
            tool_call_id="call-1",
            tool_name="echo",
        )


def test_assistant_tool_call_and_tool_result_keep_structured_link() -> None:
    call = ToolCall(call_id="call-1", name="echo", arguments={"text": "hello"})

    assistant = Message.assistant_tool_calls([call])
    result = Message.tool_result(call, '{"echo": "hello"}')

    assert assistant.tool_calls == (call,)
    assert result.role is MessageRole.TOOL
    assert result.tool_call_id == "call-1"
    assert result.tool_name == "echo"


def test_model_response_is_exactly_final_or_non_empty_tool_calls() -> None:
    assert ModelResponse.final("done").kind is ModelResponseKind.FINAL

    with pytest.raises(ValueError, match="至少需要一个"):
        ModelResponse.request_tools([])

    call = ToolCall(call_id="call-1", name="echo", arguments={})
    with pytest.raises(ValueError, match="不能同时"):
        ModelResponse(
            kind=ModelResponseKind.FINAL,
            content="done",
            tool_calls=(call,),
        )
