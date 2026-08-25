"""gRPC Servicer 与 Provider-neutral AgentLoop 之间的轻量应用门面。"""

from collections.abc import Sequence

from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.runtime.agent_loop import AgentLoop, RunResult
from devpilot_agent_service.runtime.message import Message, MessageRole
from devpilot_agent_service.tools.base import ToolDefinition


class AgentRuntimeApplication:
    """只委托既有 AgentLoop，避免 Servicer 重新实现运行状态机。"""

    def __init__(self, loop: AgentLoop) -> None:
        self._loop = loop

    def start_run(self, user_input: str) -> RunResult:
        """同步运行一次 Agent；RPC identity 不参与模型提示词或权限推导。"""

        return self._loop.run(user_input)


class DeterministicFakeModel:
    """跨语言 smoke 专用的无网络 Model；固定返回最后一条 User Message。"""

    def generate(
        self,
        messages: Sequence[Message],
        tools: Sequence[ToolDefinition],
    ) -> ModelResponse:
        del tools
        user_messages = [
            message.content for message in messages if message.role is MessageRole.USER
        ]
        if not user_messages:
            raise ValueError("fake model requires a user message")
        return ModelResponse.final(f"fake:{user_messages[-1]}")
