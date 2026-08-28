"""Tool 的注册、模型定义导出与统一执行边界。"""

import json
from collections.abc import Mapping

from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolError,
    InvalidToolArguments,
    ToolExecutionError,
    UnknownToolError,
)
from devpilot_agent_service.tools.base import JsonValue, Tool, ToolDefinition, definition_of


class ToolRegistry:
    """类似 Map<String, Tool> + Registry service，避免 Loop 硬编码工具分支。"""

    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        definition = definition_of(tool)
        if definition.name in self._tools:
            raise DuplicateToolError(definition.name)
        self._tools[definition.name] = tool

    def get(self, name: str) -> Tool:
        try:
            return self._tools[name]
        except KeyError as error:
            raise UnknownToolError(name) from error

    def definitions(self) -> tuple[ToolDefinition, ...]:
        return tuple(definition_of(tool) for tool in self._tools.values())

    def execute(
        self,
        name: str,
        arguments: Mapping[str, object],
        *,
        run_context: RunContext | None = None,
        tool_call_id: str | None = None,
    ) -> JsonValue:
        tool = self.get(name)
        if not isinstance(arguments, Mapping):
            raise InvalidToolArguments(name, "arguments must be a mapping")
        if any(not isinstance(key, str) for key in arguments):
            raise InvalidToolArguments(name, "argument keys must be strings")

        try:
            if run_context is None:
                # 第 2 章本地 Tool/Fake 保持兼容；真实 remote Tool 总会获得 run context。
                result = tool.execute(dict(arguments))
            else:
                result = tool.execute(
                    dict(arguments),
                    run_context=run_context,
                    tool_call_id=tool_call_id,
                )
        except InvalidToolArguments:
            raise
        except Exception as error:
            # Tool 实现异常在 Registry 边界统一分类，但保留 __cause__ 供本地调试。
            raise ToolExecutionError(name) from error

        try:
            json.dumps(result, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError) as error:
            raise ToolExecutionError(name) from error
        return result
