"""恢复条件与稳定故障分类；不在这里执行 Model/Tool，也不推测消息控制流。"""

import json

from devpilot_agent_service.model.errors import ProviderErrorKind
from devpilot_agent_service.runtime.errors import (
    AgentRuntimeError,
    ModelInvocationError,
    ResumeRejected,
    ToolExecutionError,
)
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.runtime.persistence import (
    RunStatus,
    RuntimeCheckpoint,
    RuntimeCheckpointState,
    RuntimeRun,
)
from devpilot_agent_service.runtime.repository import RuntimeRepositoryError

RETRYABLE_CODES = {"RUNTIME_INTERRUPTED", "TEMPORARY_MODEL_ERROR", "TEMPORARY_TOOL_ERROR"}


def classify_failure(error: Exception) -> tuple[str, bool]:
    # 仅白名单中的暂时性错误可以显式重试；unknown/auth/protocol/预算错误保持不可恢复。
    if isinstance(error, ModelInvocationError) and error.provider_kind in {
        ProviderErrorKind.TIMEOUT,
        ProviderErrorKind.UNAVAILABLE,
        ProviderErrorKind.RATE_LIMIT,
    }:
        return "TEMPORARY_MODEL_ERROR", True
    if isinstance(error, ToolExecutionError) and error.retryable:
        return "TEMPORARY_TOOL_ERROR", True
    if isinstance(error, AgentRuntimeError):
        return error.stop_reason.name, False
    return ("PERSISTENCE_ERROR" if isinstance(error, RuntimeRepositoryError) else "INTERNAL"), False


def restore_checkpoint(run: RuntimeRun, checkpoint: RuntimeCheckpoint | None):
    if (
        run.status is not RunStatus.FAILED
        or not run.retryable
        or run.failure_code not in RETRYABLE_CODES
    ):
        raise ResumeRejected("RUN_NOT_RETRYABLE")
    if checkpoint is None:
        raise ResumeRejected("CHECKPOINT_NOT_FOUND")
    if checkpoint.state_version != RuntimeCheckpointState.version:
        raise ResumeRejected("UNSUPPORTED_STATE_VERSION")
    try:
        raw = json.loads(checkpoint.state_json)
        if not isinstance(raw, dict):
            raise ValueError("invalid state")
        if raw.get("version") != RuntimeCheckpointState.version:
            raise ResumeRejected("UNSUPPORTED_STATE_VERSION")
        if not {"pending_tool_calls", "final_answer"}.issubset(raw):
            raise ValueError("missing control state")
        if any(
            not isinstance(raw[key], list)
            for key in (
                "messages",
                "pending_tool_calls",
                "completed_tool_call_ids",
            )
        ):
            raise ValueError("invalid state collections")
        state = checkpoint.state
        if not any(message.role is MessageRole.USER for message in state.messages):
            raise ValueError("missing user context")
        for field in ("current_step", "tool_call_count", "max_steps", "max_tool_calls"):
            if type(raw[field]) is not int or raw[field] < 0:
                raise ValueError("invalid counter")
        if state.max_steps < 1 or state.max_tool_calls < 1:
            raise ValueError("invalid budget")
        if type(state.redacted) is not bool or state.request_id != run.request_id:
            raise ValueError("invalid identity")
        if (state.current_step, state.tool_call_count) != (run.current_step, run.tool_call_count):
            raise ValueError("checkpoint progress mismatch")
        if state.status not in {RunStatus.PENDING, RunStatus.RUNNING, RunStatus.FAILED}:
            raise ValueError("invalid state")
        completed = state.completed_tool_call_ids
        if any(not isinstance(value, str) or not value.strip() for value in completed):
            raise ValueError("invalid tool id")
        if len(completed) != len(set(completed)) or len(completed) > state.tool_call_count:
            raise ValueError("invalid completed calls")
        result_ids = {m.tool_call_id for m in state.messages if m.role is MessageRole.TOOL}
        if not set(completed).issubset(result_ids):
            raise ValueError("completed call has no result")
        pending_ids = [call.call_id for call in state.pending_tool_calls]
        if len(pending_ids) != len(set(pending_ids)):
            raise ValueError("duplicate pending calls")
        if state.next_action == "TOOLS":
            if (
                not state.pending_tool_calls
                or state.current_step < 1
                or state.final_answer is not None
            ):
                raise ValueError("invalid tool boundary")
            # 这里只校验显式 pending 与协议消息的一致性，不从 messages 推导下一动作。
            known_calls = {c.call_id: c for m in state.messages for c in m.tool_calls}
            if any(known_calls.get(c.call_id) != c for c in state.pending_tool_calls):
                raise ValueError("pending call missing from protocol")
        elif state.next_action == "MODEL":
            if state.pending_tool_calls or state.final_answer is not None:
                raise ValueError("invalid model boundary")
        elif state.next_action == "FINALIZE":
            if state.pending_tool_calls or not isinstance(state.final_answer, str):
                raise ValueError("invalid final boundary")
        else:
            raise ValueError("invalid resume boundary")
        json.dumps(state.to_dict(), allow_nan=False)
    except (ValueError, TypeError, KeyError, AttributeError):
        raise ResumeRejected("INVALID_CHECKPOINT") from None
    if state.redacted:
        raise ResumeRejected("REDACTED_CHECKPOINT")
    if state.current_step > state.max_steps or (
        state.next_action == "MODEL" and state.current_step >= state.max_steps
    ):
        raise ResumeRejected("MAX_STEPS_EXCEEDED")
    remaining = sum(c.call_id not in completed for c in state.pending_tool_calls)
    if state.tool_call_count + remaining > state.max_tool_calls:
        raise ResumeRejected("MAX_TOOL_CALLS_EXCEEDED")
    return state
