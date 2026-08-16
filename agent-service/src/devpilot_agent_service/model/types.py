"""与具体 LLM SDK 无关的结构化模型响应类型。"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Self


class ModelResponseKind(StrEnum):
    """区分模型已经给出最终答案，还是请求 Runtime 执行工具。"""

    FINAL = "final"
    TOOL_CALLS = "tool_calls"


@dataclass(frozen=True, slots=True)
class ToolCall:
    """模型发出的结构化工具调用，不绑定任何 Provider SDK DTO。"""

    call_id: str
    name: str
    arguments: Mapping[str, object]

    def __post_init__(self) -> None:
        if not isinstance(self.call_id, str) or not self.call_id.strip():
            raise ValueError("tool call id 不能为空")
        if not isinstance(self.name, str) or not self.name.strip():
            raise ValueError("tool name 不能为空")
        if not isinstance(self.arguments, Mapping):
            raise TypeError("tool arguments 必须是 mapping")
        if any(not isinstance(key, str) for key in self.arguments):
            raise TypeError("tool argument key 必须是字符串")

        # 复制后只读，避免 Provider Adapter 在一次 generate 返回后悄悄改变调用参数。
        object.__setattr__(self, "arguments", MappingProxyType(dict(self.arguments)))


@dataclass(frozen=True, slots=True)
class ModelResponse:
    """模型的一次结构化输出，严格二选一：Final 或非空 ToolCall 列表。"""

    kind: ModelResponseKind
    content: str = ""
    tool_calls: tuple[ToolCall, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.kind, ModelResponseKind):
            raise TypeError("kind 必须是 ModelResponseKind")
        if not isinstance(self.content, str):
            raise TypeError("content 必须是字符串")
        calls = tuple(self.tool_calls)
        if any(not isinstance(tool_call, ToolCall) for tool_call in calls):
            raise TypeError("tool_calls 只能包含 ToolCall")
        if self.kind is ModelResponseKind.FINAL and calls:
            raise ValueError("final response 不能同时包含 tool calls")
        if self.kind is ModelResponseKind.TOOL_CALLS and not calls:
            raise ValueError("tool-call response 至少需要一个 tool call")
        object.__setattr__(self, "tool_calls", calls)

    @classmethod
    def final(cls, content: str) -> Self:
        return cls(kind=ModelResponseKind.FINAL, content=content)

    @classmethod
    def request_tools(
        cls,
        tool_calls: Sequence[ToolCall],
        *,
        content: str = "",
    ) -> Self:
        return cls(
            kind=ModelResponseKind.TOOL_CALLS,
            content=content,
            tool_calls=tuple(tool_calls),
        )
