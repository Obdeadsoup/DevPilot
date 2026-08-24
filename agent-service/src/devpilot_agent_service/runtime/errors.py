"""Minimal Agent Runtime 的稳定失败类型与停止原因。"""

from enum import StrEnum

from devpilot_agent_service.model.errors import ProviderErrorKind


class StopReason(StrEnum):
    MODEL_FINAL = "model_final"
    MAX_STEPS = "max_steps"
    MODEL_ERROR = "model_error"
    TOOL_ERROR = "tool_error"
    INVALID_TOOL_CALL = "invalid_tool_call"
    MAX_TOOL_CALLS = "max_tool_calls"


class AgentRuntimeError(Exception):
    """所有运行期失败的基类；调用方可通过 stop_reason 稳定分类。"""

    stop_reason: StopReason


class ModelInvocationError(AgentRuntimeError):
    stop_reason = StopReason.MODEL_ERROR

    def __init__(
        self,
        step_number: int,
        provider_kind: ProviderErrorKind = ProviderErrorKind.UNKNOWN,
    ) -> None:
        super().__init__(f"model invocation failed at step {step_number}")
        self.step_number = step_number
        self.provider_kind = provider_kind


class InvalidModelResponseError(AgentRuntimeError):
    stop_reason = StopReason.INVALID_TOOL_CALL

    def __init__(self, step_number: int) -> None:
        super().__init__(f"model returned an invalid response at step {step_number}")


class UnknownToolError(AgentRuntimeError):
    stop_reason = StopReason.INVALID_TOOL_CALL

    def __init__(self, tool_name: str) -> None:
        super().__init__(f"unknown tool: {tool_name}")
        self.tool_name = tool_name


class InvalidToolArguments(AgentRuntimeError):
    stop_reason = StopReason.INVALID_TOOL_CALL

    def __init__(self, tool_name: str, detail: str) -> None:
        super().__init__(f"invalid arguments for tool {tool_name}: {detail}")
        self.tool_name = tool_name


class ToolExecutionError(AgentRuntimeError):
    stop_reason = StopReason.TOOL_ERROR

    def __init__(self, tool_name: str) -> None:
        # 不拼接原始参数或底层异常，避免异常文本意外扩散敏感输入。
        super().__init__(f"tool execution failed: {tool_name}")
        self.tool_name = tool_name


class MaxStepsExceeded(AgentRuntimeError):
    stop_reason = StopReason.MAX_STEPS

    def __init__(self, max_steps: int) -> None:
        super().__init__(f"agent run exceeded max_steps={max_steps}")
        self.max_steps = max_steps


class MaxToolCallsExceeded(AgentRuntimeError):
    """一次 run 的累计 ToolCall 数量超过硬预算。"""

    stop_reason = StopReason.MAX_TOOL_CALLS

    def __init__(self, max_tool_calls: int) -> None:
        super().__init__(f"agent run exceeded max_tool_calls={max_tool_calls}")
        self.max_tool_calls = max_tool_calls


class DuplicateToolCallIdError(AgentRuntimeError):
    """Provider 在同一次 run 中重复使用已经出现过的 tool_call_id。"""

    stop_reason = StopReason.INVALID_TOOL_CALL

    def __init__(self) -> None:
        # 不回显由模型生成的原始 id，避免错误通道承载不可控内容。
        super().__init__("model returned a duplicate tool_call_id")


class DuplicateToolError(ValueError):
    def __init__(self, tool_name: str) -> None:
        super().__init__(f"duplicate tool: {tool_name}")
        self.tool_name = tool_name
