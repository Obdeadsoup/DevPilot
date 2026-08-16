"""不访问网络、不消耗 Token 的脚本化 Model Fake。"""

from collections.abc import Sequence
from dataclasses import dataclass

from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.tools.base import ToolDefinition


@dataclass(frozen=True, slots=True)
class RecordedModelCall:
    messages: tuple[Message, ...]
    tools: tuple[ToolDefinition, ...]


class FakeModel:
    """类似 Mockito Stub/Fake：按脚本顺序返回响应或抛错，并保存每次输入快照。"""

    def __init__(self, script: Sequence[ModelResponse | Exception]) -> None:
        self._script = tuple(script)
        self._cursor = 0
        self.calls: list[RecordedModelCall] = []

    def generate(
        self,
        messages: Sequence[Message],
        tools: Sequence[ToolDefinition],
    ) -> ModelResponse:
        # 先记录不可变快照，测试才能证明 Tool Result 确实进入了下一轮上下文。
        self.calls.append(RecordedModelCall(tuple(messages), tuple(tools)))
        if self._cursor >= len(self._script):
            raise AssertionError("FakeModel script exhausted")

        scripted = self._script[self._cursor]
        self._cursor += 1
        if isinstance(scripted, Exception):
            raise scripted
        return scripted
