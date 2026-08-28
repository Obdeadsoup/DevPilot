"""仅用于学习和本地测试的无副作用 Echo Tool。"""

from collections.abc import Mapping

from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import InvalidToolArguments
from devpilot_agent_service.tools.base import JsonValue


class EchoTool:
    """返回输入文本，不访问网络、文件、数据库或 DevPilot 业务服务。"""

    @property
    def name(self) -> str:
        return "echo"

    @property
    def description(self) -> str:
        return "Return the provided text without side effects."

    @property
    def parameter_schema(self) -> Mapping[str, object]:
        return {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
            "additionalProperties": False,
        }

    def execute(
        self,
        arguments: Mapping[str, object],
        *,
        run_context: RunContext | None = None,
        tool_call_id: str | None = None,
    ) -> JsonValue:
        del run_context, tool_call_id
        if set(arguments) != {"text"}:
            raise InvalidToolArguments(self.name, "exactly one 'text' argument is required")
        text = arguments["text"]
        if not isinstance(text, str):
            raise InvalidToolArguments(self.name, "'text' must be a string")
        return {"echo": text}
