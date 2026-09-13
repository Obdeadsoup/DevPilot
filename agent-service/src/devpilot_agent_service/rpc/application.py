"""gRPC Servicer 与 Provider-neutral AgentLoop 之间的轻量应用门面。"""

import time
from collections.abc import Callable, Sequence

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.agent_loop import AgentLoop, PreparedRun, RunResult
from devpilot_agent_service.runtime.cancellation import CancellationToken
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.events import RuntimeEvent
from devpilot_agent_service.runtime.message import Message, MessageRole
from devpilot_agent_service.tools.base import ToolDefinition


class AgentRuntimeApplication:
    """只委托既有 AgentLoop，避免 Servicer 重新实现运行状态机。"""

    def __init__(self, loop: AgentLoop, close_callback: Callable[[], None] | None = None) -> None:
        self._loop = loop
        self._close_callback = close_callback

    def start_run(
        self,
        user_input: str,
        *,
        run_context: RunContext | None = None,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ) -> RunResult:
        """同步运行一次 Agent；RPC identity 不参与模型提示词或权限推导。"""

        return self._loop.run(
            user_input,
            run_context=run_context,
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    def prepare_run(self, user_input: str, run_context: RunContext) -> PreparedRun:
        return self._loop.prepare_run(user_input, run_context=run_context)

    def prepare_resume(self, run_context: RunContext) -> PreparedRun:
        return self._loop.prepare_resume(run_context)

    def prepare_approval_resume(
        self, run_context: RunContext, proposal_id: str
    ) -> PreparedRun:
        return self._loop.prepare_approval_resume(run_context, proposal_id)

    def execute_prepared(
        self,
        prepared: PreparedRun,
        *,
        on_event=None,
        cancellation_token=None,
    ) -> RunResult:
        return self._loop.execute_prepared(
            prepared,
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    def request_cancel(self, run_id: str, request_id: str):
        return self._loop.repository.request_cancel(run_id, request_id)

    def reconcile_interrupted_runs(self) -> tuple[str, ...]:
        return self._loop.repository.reconcile_interrupted_runs()

    def close(self) -> None:
        """关闭进程级 Tool Channel；每次 ToolCall 不创建/销毁连接。"""

        if self._close_callback is not None:
            self._close_callback()


class DeterministicFakeModel:
    """跨语言 smoke 专用的无网络 Model；可选地强制经过一次只读 Tool。"""

    def __init__(self, delay_seconds: float = 0.0, tool_name: str | None = None) -> None:
        if delay_seconds < 0:
            raise ValueError("fake model delay must not be negative")
        self._delay_seconds = delay_seconds
        self._tool_name = tool_name

    def generate(
        self,
        messages: Sequence[Message],
        tools: Sequence[ToolDefinition],
    ) -> ModelResponse:
        del tools
        if self._delay_seconds:
            time.sleep(self._delay_seconds)
        user_messages = [
            message.content for message in messages if message.role is MessageRole.USER
        ]
        if not user_messages:
            raise ValueError("fake model requires a user message")
        if self._tool_name and not any(
            message.role is MessageRole.TOOL for message in messages
        ):
            arguments = {} if self._tool_name == "project.get_summary" else {"limit": 5}
            return ModelResponse.request_tools(
                [ToolCall("fake-tool-call-1", self._tool_name, arguments)]
            )
        if self._tool_name:
            return ModelResponse.final(f"fake-tool:{self._tool_name}:ok")
        return ModelResponse.final(f"fake:{user_messages[-1]}")
