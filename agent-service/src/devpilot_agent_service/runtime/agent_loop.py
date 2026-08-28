"""同步、单 Agent、结构化 ToolCall 的最小运行循环。"""

import json
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from devpilot_agent_service.model.base import Model
from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ModelResponseKind
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidModelResponseError,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
    ModelInvocationError,
    StopReason,
)
from devpilot_agent_service.runtime.events import RuntimeEvent, RuntimeEventType
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
        max_tool_calls: int = 16,
        system_prompt: str | None = None,
    ) -> None:
        if not isinstance(max_steps, int) or isinstance(max_steps, bool) or max_steps < 1:
            raise ValueError("max_steps 必须是正整数")
        if (
            not isinstance(max_tool_calls, int)
            or isinstance(max_tool_calls, bool)
            or max_tool_calls < 1
        ):
            raise ValueError("max_tool_calls 必须是正整数")
        if system_prompt is not None and not isinstance(system_prompt, str):
            raise TypeError("system_prompt 必须是字符串或 None")
        self._model = model
        self._registry = registry
        self._max_steps = max_steps
        self._max_tool_calls = max_tool_calls
        self._system_prompt = system_prompt

    def run(
        self,
        user_input: str,
        *,
        history: Sequence[Message] = (),
        run_context: RunContext | None = None,
        on_event: Callable[[RuntimeEvent], None] | None = None,
    ) -> RunResult:
        """推进到 Final；可选 hook 只观察公开生命周期，不改变 Provider-neutral 编排。"""

        if not isinstance(user_input, str):
            raise TypeError("user_input 必须是字符串")
        if any(not isinstance(message, Message) for message in history):
            raise TypeError("history 只能包含 Message")
        if run_context is not None and not isinstance(run_context, RunContext):
            raise TypeError("run_context 必须是 RunContext 或 None")
        if on_event is not None and not callable(on_event):
            raise TypeError("on_event 必须可调用或为 None")

        messages: list[Message] = []
        if self._system_prompt is not None:
            messages.append(Message.system(self._system_prompt))
        messages.extend(history)
        messages.append(Message.user(user_input))
        trace: list[RuntimeTraceStep] = []
        executed_tool_call_ids: set[str] = set()
        tool_call_count = 0

        # 有界 for-loop 是运行时硬停止线；模型持续请求工具也不能形成无限循环。
        for step_number in range(1, self._max_steps + 1):
            _emit(on_event, RuntimeEvent(RuntimeEventType.MODEL_STEP_STARTED, step_number))
            try:
                response = self._model.generate(
                    tuple(messages),
                    self._registry.definitions(),
                )
            except ProviderError as error:
                raise ModelInvocationError(step_number, error.kind) from error
            except Exception as error:
                raise ModelInvocationError(step_number, ProviderErrorKind.UNKNOWN) from error

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

            current_ids = [tool_call.call_id for tool_call in response.tool_calls]
            if len(current_ids) != len(set(current_ids)) or any(
                call_id in executed_tool_call_ids for call_id in current_ids
            ):
                # 整批预检，避免一个响应的前半批已产生副作用后才发现重复 id。
                raise DuplicateToolCallIdError()

            next_tool_call_count = tool_call_count + len(response.tool_calls)
            if next_tool_call_count > self._max_tool_calls:
                # 预算同样按整批预检，超限响应中的工具一个也不执行。
                raise MaxToolCallsExceeded(self._max_tool_calls)

            # 先回填 assistant 的结构化调用，再逐个追加对应 Tool Result，Provider Adapter
            # 因而能重建完整协议，而不需要解析 Thought:/Action: 文本。
            messages.append(
                Message.assistant_tool_calls(
                    response.tool_calls,
                    content=response.content,
                )
            )
            for tool_call in response.tool_calls:
                executed_tool_call_ids.add(tool_call.call_id)
                _emit(
                    on_event,
                    RuntimeEvent(RuntimeEventType.TOOL_STARTED, step_number, tool_call.name),
                )
                result = self._registry.execute(
                    tool_call.name,
                    tool_call.arguments,
                    run_context=run_context,
                    tool_call_id=tool_call.call_id,
                )
                _emit(
                    on_event,
                    RuntimeEvent(RuntimeEventType.TOOL_COMPLETED, step_number, tool_call.name),
                )
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
            tool_call_count = next_tool_call_count
            trace.append(
                RuntimeTraceStep(
                    step_number=step_number,
                    response_kind=response.kind,
                    tool_names=tuple(call.name for call in response.tool_calls),
                )
            )

        raise MaxStepsExceeded(self._max_steps)


def _emit(
    on_event: Callable[[RuntimeEvent], None] | None,
    event: RuntimeEvent,
) -> None:
    # Hook 由 RPC Queue bridge 提供；AgentLoop 本身不认识 runId、protobuf 或 gRPC。
    if on_event is not None:
        on_event(event)
