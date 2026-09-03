"""同步、单 Agent、结构化 ToolCall 的最小运行循环。"""

import json
import logging
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace
from uuid import uuid4

from devpilot_agent_service.model.base import Model
from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ModelResponseKind
from devpilot_agent_service.runtime.cancellation import CancellationToken
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidModelResponseError,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
    ModelInvocationError,
    ResumeRejected,
    RunCancelled,
    StopReason,
)
from devpilot_agent_service.runtime.events import RuntimeEvent, RuntimeEventType
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.runtime.persistence import (
    RunStatus,
    RuntimeCheckpointState,
    RuntimeStep,
    StepType,
    message_to_dict,
)
from devpilot_agent_service.runtime.recovery import classify_failure, restore_checkpoint
from devpilot_agent_service.runtime.repository import AgentRuntimeRepository, RuntimeStateConflict
from devpilot_agent_service.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)


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
    run_id: str


@dataclass(frozen=True, slots=True)
class PreparedRun:
    run_id: str
    state: RuntimeCheckpointState
    run_context: RunContext | None


class AgentLoop:
    """类似 orchestration service：在模型、工具和停止条件之间推进有限状态循环。"""

    def __init__(
        self,
        model: Model,
        registry: ToolRegistry,
        *,
        repository: AgentRuntimeRepository,
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
        self._repository = repository

    def run(
        self,
        user_input: str,
        *,
        history: Sequence[Message] = (),
        run_context: RunContext | None = None,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ) -> RunResult:
        return self.execute_prepared(
            self.prepare_run(user_input, history=history, run_context=run_context),
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    @property
    def repository(self) -> AgentRuntimeRepository:
        return self._repository

    def prepare_run(
        self,
        user_input: str,
        *,
        history: Sequence[Message] = (),
        run_context: RunContext | None = None,
    ) -> PreparedRun:
        if not isinstance(user_input, str):
            raise TypeError("user_input 必须是字符串")
        if any(not isinstance(message, Message) for message in history):
            raise TypeError("history 只能包含 Message")
        if run_context is not None and not isinstance(run_context, RunContext):
            raise TypeError("run_context 必须是 RunContext 或 None")
        messages = [Message.system(self._system_prompt)] if self._system_prompt else []
        messages.extend(history)
        messages.append(Message.user(user_input))
        run_id = run_context.run_id if run_context else str(uuid4())
        state = RuntimeCheckpointState(
            messages=tuple(messages),
            current_step=0,
            tool_call_count=0,
            completed_tool_call_ids=(),
            status=RunStatus.RUNNING,
            next_action="MODEL",
            max_steps=self._max_steps,
            max_tool_calls=self._max_tool_calls,
            request_id=run_context.request_id if run_context else None,
        )
        # 在发 RUN_STARTED/启动 worker 前留下 Run 和初始控制状态，关闭取消的注册空窗。
        with self._repository.transaction():
            self._repository.create_run(run_id, state.request_id)
            self._repository.update_run_status(run_id, RunStatus.RUNNING)
            self._repository.save_checkpoint(run_id, 0, state)
        return PreparedRun(run_id, state, run_context)

    def prepare_resume(self, run_context: RunContext) -> PreparedRun:
        repository = self._repository
        with repository.transaction():
            run = repository.get_run(run_context.run_id)
            if run is None or run.request_id != run_context.request_id:
                raise ResumeRejected("RUN_NOT_FOUND")
            checkpoint = repository.get_latest_checkpoint(run.run_id)
            state = restore_checkpoint(run, checkpoint)
            steps = repository.list_steps(run.run_id)
            if checkpoint.after_step != (steps[-1].step_no if steps else 0):
                raise ResumeRejected("INVALID_CHECKPOINT")
            if not repository.compare_and_set_status(
                run.run_id,
                (RunStatus.FAILED,),
                RunStatus.RUNNING,
                expected_version=run.version,
            ):
                raise ResumeRejected("RESUME_CONFLICT")
            state = replace(state, status=RunStatus.RUNNING)
            repository.save_checkpoint(run.run_id, checkpoint.after_step, state)
        return PreparedRun(run.run_id, state, run_context)

    def resume(
        self,
        run_context: RunContext,
        *,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ) -> RunResult:
        return self.execute_prepared(
            self.prepare_resume(run_context),
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    def execute_prepared(
        self,
        prepared: PreparedRun,
        *,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ) -> RunResult:
        repository = self._repository
        run_id, state, run_context = prepared.run_id, prepared.state, prepared.run_context
        token = cancellation_token or CancellationToken()
        trace: list[RuntimeTraceStep] = []
        active_step: RuntimeStep | None = None
        latest = repository.get_latest_checkpoint(run_id)
        after_step = latest.after_step

        def safe_point():
            # Event 缩短同进程响应时间，数据库是持久取消意图的权威；断流不会设置任一项。
            run = repository.get_run(run_id)
            if run.status in {RunStatus.CANCEL_REQUESTED, RunStatus.CANCELLED}:
                token.cancel()
            if token.is_cancelled:
                repository.request_cancel(run_id, state.request_id)
                raise RunCancelled()
            if run.status is not RunStatus.RUNNING:
                raise RuntimeStateConflict("run is no longer executing")

        def save_boundary():
            nonlocal state
            run = repository.get_run(run_id)
            # 取消可以在阻塞 I/O 中到达；保留已经成功的动作，不能把 CANCEL_REQUESTED 写回 RUNNING。
            if run.status not in {RunStatus.RUNNING, RunStatus.CANCEL_REQUESTED}:
                raise RuntimeStateConflict("run is no longer executing")
            state = replace(state, status=run.status)
            repository.update_run_status(
                run_id,
                run.status,
                current_step=state.current_step,
                tool_call_count=state.tool_call_count,
            )
            repository.save_checkpoint(run_id, after_step, state)

        def begin_step(step_type: StepType, input: object):
            nonlocal after_step
            with repository.transaction():
                safe_point()
                step = repository.create_step(run_id, step_type, input)
                after_step = step.step_no
                # 开始记录也带控制快照；崩溃后的重试消耗新的预算，不重置已开始的尝试次数。
                save_boundary()
            return step

        try:
            if on_event is not None and not callable(on_event):
                raise TypeError("on_event 必须可调用或为 None")
            while True:
                safe_point()
                if state.next_action == "MODEL":
                    if state.current_step >= state.max_steps:
                        raise MaxStepsExceeded(state.max_steps)
                    state = replace(state, current_step=state.current_step + 1)
                    active_step = begin_step(
                        StepType.MODEL_CALL,
                        {
                            "messages": [message_to_dict(m) for m in state.messages],
                        },
                    )
                    _emit(
                        on_event,
                        RuntimeEvent(RuntimeEventType.MODEL_STEP_STARTED, state.current_step),
                    )
                    safe_point()
                    try:
                        response = self._model.generate(
                            state.messages, self._registry.definitions()
                        )
                    except ProviderError as error:
                        raise ModelInvocationError(state.current_step, error.kind) from error
                    except Exception as error:
                        raise ModelInvocationError(
                            state.current_step, ProviderErrorKind.UNKNOWN
                        ) from error
                    if not isinstance(response, ModelResponse):
                        raise InvalidModelResponseError(state.current_step)
                    final = response.kind is ModelResponseKind.FINAL
                    if not final:
                        ids = [c.call_id for c in response.tool_calls]
                        if len(ids) != len(set(ids)) or any(
                            value in state.completed_tool_call_ids for value in ids
                        ):
                            raise DuplicateToolCallIdError()
                        if state.tool_call_count + len(ids) > state.max_tool_calls:
                            raise MaxToolCallsExceeded(state.max_tool_calls)
                    message = (
                        Message.assistant(response.content)
                        if final
                        else Message.assistant_tool_calls(
                            response.tool_calls, content=response.content
                        )
                    )
                    state = replace(
                        state,
                        messages=(*state.messages, message),
                        pending_tool_calls=response.tool_calls,
                        final_answer=response.content if final else None,
                        next_action="FINALIZE" if final else "TOOLS",
                    )
                    with repository.transaction():
                        repository.finish_step(active_step.step_id, message_to_dict(message))
                        save_boundary()
                    active_step = None
                    # 成功动作和快照先原子提交，随后立即观察取消，绝不启动后续 Tool。
                    safe_point()
                elif state.next_action == "TOOLS":
                    # 按显式 pending 分派；仅跳过 Runtime 已确认完成的调用，不猜 messages。
                    pending = tuple(
                        c
                        for c in state.pending_tool_calls
                        if c.call_id not in state.completed_tool_call_ids
                    )
                    if state.tool_call_count + len(pending) > state.max_tool_calls:
                        raise MaxToolCallsExceeded(state.max_tool_calls)
                    for call in pending:
                        safe_point()
                        state = replace(state, tool_call_count=state.tool_call_count + 1)
                        active_step = begin_step(
                            StepType.TOOL_CALL,
                            {
                                "call_id": call.call_id,
                                "name": call.name,
                                "arguments": dict(call.arguments),
                            },
                        )
                        _emit(
                            on_event,
                            RuntimeEvent(
                                RuntimeEventType.TOOL_STARTED, state.current_step, call.name
                            ),
                        )
                        safe_point()
                        result = self._registry.execute(
                            call.name,
                            call.arguments,
                            run_context=run_context,
                            tool_call_id=call.call_id,
                        )
                        # Java 成功后、这里提交前仍有跨服务不确定窗口。
                        # 未来写 Tool 必须在 Java 业务边界做幂等。
                        completed = (*state.completed_tool_call_ids, call.call_id)
                        remaining = tuple(
                            c for c in state.pending_tool_calls if c.call_id not in completed
                        )
                        state = replace(
                            state,
                            completed_tool_call_ids=completed,
                            pending_tool_calls=remaining,
                            next_action="TOOLS" if remaining else "MODEL",
                            messages=(
                                *state.messages,
                                Message.tool_result(
                                    call,
                                    json.dumps(
                                        result,
                                        ensure_ascii=False,
                                        sort_keys=True,
                                        allow_nan=False,
                                    ),
                                ),
                            ),
                        )
                        with repository.transaction():
                            repository.finish_step(active_step.step_id, result)
                            save_boundary()
                        active_step = None
                        safe_point()
                        _emit(
                            on_event,
                            RuntimeEvent(
                                RuntimeEventType.TOOL_COMPLETED, state.current_step, call.name
                            ),
                        )
                    if not pending:
                        state = replace(state, pending_tool_calls=(), next_action="MODEL")
                        with repository.transaction():
                            save_boundary()
                    trace.append(
                        RuntimeTraceStep(
                            state.current_step,
                            ModelResponseKind.TOOL_CALLS,
                            tuple(c.name for c in pending),
                        )
                    )
                elif state.next_action == "FINALIZE":
                    with repository.transaction():
                        safe_point()
                        repository.update_run_status(
                            run_id,
                            RunStatus.SUCCEEDED,
                            current_step=state.current_step,
                            tool_call_count=state.tool_call_count,
                        )
                        state = replace(state, status=RunStatus.SUCCEEDED, next_action="TERMINAL")
                        repository.save_checkpoint(run_id, after_step, state)
                    trace.append(
                        RuntimeTraceStep(
                            state.current_step,
                            ModelResponseKind.FINAL,
                            stop_reason=StopReason.MODEL_FINAL,
                        )
                    )
                    return RunResult(
                        state.final_answer,
                        StopReason.MODEL_FINAL,
                        state.messages,
                        tuple(trace),
                        run_id,
                    )
                else:
                    raise ResumeRejected("INVALID_CHECKPOINT")
        except Exception as error:
            code, retryable = classify_failure(error)
            cancelled = isinstance(error, RunCancelled)
            try:
                with repository.transaction():
                    run = repository.get_run(run_id)
                    if cancelled or token.is_cancelled:
                        repository.request_cancel(run_id, state.request_id)
                        run = repository.get_run(run_id)
                    cancelled = run.status in {RunStatus.CANCEL_REQUESTED, RunStatus.CANCELLED}
                    if run.status in {RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED}:
                        if cancelled:
                            raise RunCancelled()
                        raise RuntimeStateConflict("run already terminal")
                    if active_step is not None:
                        repository.fail_step(
                            active_step.step_id, {"code": "CANCELLED" if cancelled else code}
                        )
                    status = RunStatus.CANCELLED if cancelled else RunStatus.FAILED
                    repository.update_run_status(
                        run_id,
                        status,
                        current_step=state.current_step,
                        tool_call_count=state.tool_call_count,
                        retryable=retryable and not cancelled,
                        failure_code=None if cancelled else code,
                        failure_message=None if cancelled else "runtime execution failed",
                    )
                    state = replace(
                        state,
                        status=status,
                        next_action=state.next_action
                        if retryable and not cancelled
                        else "TERMINAL",
                    )
                    repository.save_checkpoint(run_id, after_step, state)
            except Exception:
                LOGGER.error("Runtime failure could not be persisted")
            if cancelled:
                raise RunCancelled() from None
            raise


def _emit(on_event: Callable[[RuntimeEvent], None] | None, event: RuntimeEvent) -> None:
    if on_event is not None:
        on_event(event)
