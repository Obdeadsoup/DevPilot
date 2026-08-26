"""AgentLoop 对外提供的 Provider-neutral 生命周期事件。"""

from dataclasses import dataclass
from enum import StrEnum


class RuntimeEventType(StrEnum):
    MODEL_STEP_STARTED = "model_step_started"
    TOOL_STARTED = "tool_started"
    TOOL_COMPLETED = "tool_completed"


@dataclass(frozen=True, slots=True)
class RuntimeEvent:
    """只描述可公开控制流，不携带 reasoning、Prompt、Tool 参数或执行结果。"""

    type: RuntimeEventType
    step: int
    tool_name: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.type, RuntimeEventType):
            raise TypeError("type 必须是 RuntimeEventType")
        if not isinstance(self.step, int) or isinstance(self.step, bool) or self.step < 1:
            raise ValueError("step 必须是正整数")
        requires_tool = self.type in {
            RuntimeEventType.TOOL_STARTED,
            RuntimeEventType.TOOL_COMPLETED,
        }
        if requires_tool and (not isinstance(self.tool_name, str) or not self.tool_name):
            raise ValueError("Tool 事件必须携带 tool_name")
        if not requires_tool and self.tool_name is not None:
            raise ValueError("Model 事件不能携带 tool_name")
