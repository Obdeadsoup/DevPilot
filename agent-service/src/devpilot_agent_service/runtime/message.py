"""Agent Runtime 内部统一使用的消息语义模型。"""

from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Self

from devpilot_agent_service.model.types import ToolCall


class MessageRole(StrEnum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


@dataclass(frozen=True, slots=True)
class Message:
    """隔离 Runtime 与 Provider DTO，并约束 ToolCall/ToolResult 的关联字段。"""

    role: MessageRole
    content: str
    tool_calls: tuple[ToolCall, ...] = ()
    tool_call_id: str | None = None
    tool_name: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.role, MessageRole):
            raise TypeError("role 必须是 MessageRole")
        if not isinstance(self.content, str):
            raise TypeError("content 必须是字符串")
        calls = tuple(self.tool_calls)
        if any(not isinstance(tool_call, ToolCall) for tool_call in calls):
            raise TypeError("tool_calls 只能包含 ToolCall")
        if calls and self.role is not MessageRole.ASSISTANT:
            raise ValueError("只有 assistant message 可以携带 tool calls")

        if self.role is MessageRole.TOOL:
            if not self.tool_call_id or not self.tool_name:
                raise ValueError("tool message 必须关联 tool_call_id 和 tool_name")
            if calls:
                raise ValueError("tool result message 不能再发起 tool calls")
        elif self.tool_call_id is not None or self.tool_name is not None:
            raise ValueError("非 tool message 不能携带 tool result 关联字段")
        object.__setattr__(self, "tool_calls", calls)

    @classmethod
    def system(cls, content: str) -> Self:
        return cls(role=MessageRole.SYSTEM, content=content)

    @classmethod
    def user(cls, content: str) -> Self:
        return cls(role=MessageRole.USER, content=content)

    @classmethod
    def assistant(cls, content: str) -> Self:
        return cls(role=MessageRole.ASSISTANT, content=content)

    @classmethod
    def assistant_tool_calls(
        cls,
        tool_calls: Sequence[ToolCall],
        *,
        content: str = "",
    ) -> Self:
        calls = tuple(tool_calls)
        if not calls:
            raise ValueError("assistant tool-call message 至少需要一个 tool call")
        return cls(role=MessageRole.ASSISTANT, content=content, tool_calls=calls)

    @classmethod
    def tool_result(cls, call: ToolCall, content: str) -> Self:
        return cls(
            role=MessageRole.TOOL,
            content=content,
            tool_call_id=call.call_id,
            tool_name=call.name,
        )
