"""同步、单 Agent、结构化 ToolCall 的最小运行循环。"""

import json
from collections.abc import Sequence
from dataclasses import dataclass

from devpilot_agent_service.model.base import Model
from devpilot_agent_service.model.types import ModelResponse, ModelResponseKind
from devpilot_agent_service.runtime.errors import (
    InvalidModelResponseError,
    MaxStepsExceeded,
    ModelInvocationError,
    StopReason,
)
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.tools.registry import ToolRegistry


@dataclass(frozen=True, slots=True)
class RuntimeTraceStep:
    """只记录控制流，不保存私有 reasoning、凭据或完整敏感 Payload。"""

    step_number: int
    response_kind: ModelResponseKind
    tool_names: tuple[str, ...] = ()
    stop_reason: StopReason | None = None


@dataclass(frozen=True, slots=True)
class RunResult:
    final_answer: str
    stop_reason: StopReason
    messages: tuple[Message, ...]
    trace: tuple[RuntimeTraceStep, ...]


class AgentLoop:
    """类似 orchestration service：在模型、工具和停止条件之间推进有限状态循环。"""

    def __init__(
        self,
        model: Model,
        registry: ToolRegistry,
        *,
        max_steps: int = 8,
        system_prompt: str | None = None,
    ) -> None:
        if not isinstance(max_steps, int) or isinstance(max_steps, bool) or max_steps < 1:
            raise ValueError("max_steps 必须是正整数")
        if system_prompt is not None and not isinstance(system_prompt, str):
            raise TypeError("system_prompt 必须是字符串或 None")
        self._model = model
        self._registry = registry
        self._max_steps = max_steps
        self._system_prompt = system_prompt

    def run(
        self,
        user_input: str,
        *,
        history: Sequence[Message] = (),
    ) -> RunResult:
        """从用户消息开始，直到模型 Final 或稳定失败，最多调用模型 max_steps 次。"""

        if not isinstance(user_input, str):
            raise TypeError("user_input 必须是字符串")
        if any(not isinstance(message, Message) for message in history):
            raise TypeError("history 只能包含 Message")

        messages: list[Message] = []
        if self._system_prompt is not None:
            messages.append(Message.system(self._system_prompt))
        messages.extend(history)
        messages.append(Message.user(user_input))
        trace: list[RuntimeTraceStep] = []

        # 有界 for-loop 是运行时硬停止线；模型持续请求工具也不能形成无限循环。
        for step_number in range(1, self._max_steps + 1):
            try:
                response = self._model.generate(
                    tuple(messages),
                    self._registry.definitions(),
                )
            except Exception as error:
                raise ModelInvocationError(step_number) from error

            if not isinstance(response, ModelResponse):
                raise InvalidModelResponseError(step_number)

            if response.kind is ModelResponseKind.FINAL:
                messages.append(Message.assistant(response.content))
                trace.append(
                    RuntimeTraceStep(
                        step_number=step_number,
                        response_kind=response.kind,
                        stop_reason=StopReason.MODEL_FINAL,
                    )
                )
                return RunResult(
                    final_answer=response.content,
                    stop_reason=StopReason.MODEL_FINAL,
                    messages=tuple(messages),
                    trace=tuple(trace),
                )

            # 先回填 assistant 的结构化调用，再逐个追加对应 Tool Result，Provider Adapter
            # 因而能重建完整协议，而不需要解析 Thought:/Action: 文本。
            messages.append(
                Message.assistant_tool_calls(
                    response.tool_calls,
                    content=response.content,
                )
            )
            for tool_call in response.tool_calls:
                result = self._registry.execute(tool_call.name, tool_call.arguments)
                messages.append(
                    Message.tool_result(
                        tool_call,
                        json.dumps(
                            result,
                            ensure_ascii=False,
                            sort_keys=True,
                            allow_nan=False,
                        ),
                    )
                )
            trace.append(
                RuntimeTraceStep(
                    step_number=step_number,
                    response_kind=response.kind,
                    tool_names=tuple(call.name for call in response.tool_calls),
                )
            )

        raise MaxStepsExceeded(self._max_steps)
