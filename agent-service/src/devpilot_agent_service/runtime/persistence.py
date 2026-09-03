"""Provider-neutral Runtime 事实和显式、可版本化的恢复状态。"""

import json
from dataclasses import dataclass
from enum import StrEnum
from typing import ClassVar, Self

from devpilot_agent_service.model.types import ToolCall
from devpilot_agent_service.runtime.message import Message, MessageRole


class RunStatus(StrEnum):
    # 仅 FAILED + retryable 经显式 Resume 校验后可重新进入 RUNNING。
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    CANCEL_REQUESTED = "CANCEL_REQUESTED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class StepType(StrEnum):
    MODEL_CALL = "MODEL_CALL"
    TOOL_CALL = "TOOL_CALL"


class StepStatus(StrEnum):
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class RuntimeRun:
    run_id: str
    status: RunStatus
    current_step: int  # Model 轮次，保持原 max_steps / RuntimeEvent.step 语义。
    tool_call_count: int
    created_at: str
    updated_at: str
    started_at: str | None
    finished_at: str | None
    failure_code: str | None
    failure_message: str | None
    request_id: str | None = None
    retryable: bool = False
    version: int = 0


@dataclass(frozen=True, slots=True)
class RuntimeStep:
    step_id: str
    run_id: str
    step_no: int  # Model 和 Tool 共用一个独立的执行序号。
    step_type: StepType
    status: StepStatus
    started_at: str
    finished_at: str | None
    input: object
    output: object
    error: object


@dataclass(frozen=True, slots=True)
class CancelDecision:
    run: RuntimeRun
    accepted: bool


def message_to_dict(message: Message) -> dict[str, object]:
    return {
        "role": message.role.value,
        "content": message.content,
        "tool_calls": [
            {"call_id": call.call_id, "name": call.name, "arguments": dict(call.arguments)}
            for call in message.tool_calls
        ],
        "tool_call_id": message.tool_call_id,
        "tool_name": message.tool_name,
    }


@dataclass(frozen=True, slots=True)
class RuntimeCheckpointState:
    version: ClassVar[int] = 2
    messages: tuple[Message, ...]
    current_step: int
    tool_call_count: int
    completed_tool_call_ids: tuple[str, ...]
    status: RunStatus
    next_action: str  # MODEL / TOOLS / FINALIZE / TERMINAL：恢复时直接分派，不能猜 messages。
    max_steps: int
    max_tool_calls: int
    request_id: str | None = None
    redacted: bool = False
    pending_tool_calls: tuple[ToolCall, ...] = ()
    final_answer: str | None = None

    def to_dict(self) -> dict[str, object]:
        return {
            "version": self.version,
            "messages": [message_to_dict(message) for message in self.messages],
            "current_step": self.current_step,
            "tool_call_count": self.tool_call_count,
            "completed_tool_call_ids": list(self.completed_tool_call_ids),
            "status": self.status.value,
            "next_action": self.next_action,
            "max_steps": self.max_steps,
            "max_tool_calls": self.max_tool_calls,
            "request_id": self.request_id,
            "redacted": self.redacted,
            "pending_tool_calls": [
                {"call_id": c.call_id, "name": c.name, "arguments": dict(c.arguments)}
                for c in self.pending_tool_calls
            ],
            "final_answer": self.final_answer,
        }

    @classmethod
    def from_json(cls, state_json: str) -> Self:
        data = json.loads(state_json)
        # 显式拒绝旧版/未知版本，避免把缺失控制字段的状态当作 v2 重放。
        if not isinstance(data, dict) or type(data.get("version")) is not int:
            raise ValueError("invalid checkpoint")
        if data["version"] != cls.version:
            raise ValueError("unsupported runtime checkpoint version")
        if data["next_action"] not in {"MODEL", "TOOLS", "FINALIZE", "TERMINAL"}:
            raise ValueError("invalid checkpoint next_action")
        messages = tuple(
            Message(
                role=MessageRole(item["role"]),
                content=item["content"],
                tool_calls=tuple(ToolCall(**call) for call in item["tool_calls"]),
                tool_call_id=item["tool_call_id"],
                tool_name=item["tool_name"],
            )
            for item in data.pop("messages")
        )
        data.pop("version")
        data["status"] = RunStatus(data["status"])
        data["completed_tool_call_ids"] = tuple(data["completed_tool_call_ids"])
        data["pending_tool_calls"] = tuple(
            ToolCall(**call)
            for call in data.pop(
                "pending_tool_calls",
                [],
            )
        )
        return cls(messages=messages, **data)


@dataclass(frozen=True, slots=True)
class RuntimeCheckpoint:
    checkpoint_id: str
    run_id: str
    checkpoint_no: int
    after_step: int
    state_version: int
    state_json: str
    created_at: str

    @property
    def state(self) -> RuntimeCheckpointState:
        if self.state_version != RuntimeCheckpointState.version:
            raise ValueError("unsupported runtime checkpoint version")
        return RuntimeCheckpointState.from_json(self.state_json)
