"""AgentLoop 依赖的模型能力接口。"""

from collections.abc import Sequence
from typing import Protocol

from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.tools.base import ToolDefinition


class Model(Protocol):
    """类似 Java interface；具体 Provider Adapter 负责 SDK DTO 的双向转换。"""

    def generate(
        self,
        messages: Sequence[Message],
        tools: Sequence[ToolDefinition],
    ) -> ModelResponse:
        """基于完整消息上下文和可用工具定义产生一次结构化响应。"""
