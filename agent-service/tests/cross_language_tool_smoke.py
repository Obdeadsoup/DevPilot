"""由 Java Surefire 启动的独立 Python 进程：验证 Tool wire 与 AgentLoop remote Tool 主链。"""

from collections.abc import Sequence
from pathlib import Path
from tempfile import TemporaryDirectory

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository
from devpilot_agent_service.tools.base import ToolDefinition
from devpilot_agent_service.tools.devpilot import ListOpenTasksTool
from devpilot_agent_service.tools.registry import ToolRegistry


class ToolThenFinalModel:
    def __init__(self) -> None:
        self.calls = 0

    def generate(
        self, messages: Sequence[Message], tools: Sequence[ToolDefinition]
    ) -> ModelResponse:
        self.calls += 1
        if self.calls == 1:
            return ModelResponse.request_tools(
                [ToolCall("loop-call", "task.list_open", {"limit": 2})]
            )
        if not any(message.tool_call_id == "loop-call" for message in messages):
            raise AssertionError("remote Tool result did not return to model")
        return ModelResponse.final("remote-tool-final")


def main() -> int:
    context = RunContext("cross-language-run", "cross-language-request")
    with (
        JavaToolGatewayClient(JavaToolGatewayConfig.from_env()) as client,
        TemporaryDirectory() as runtime_dir,
    ):
        for index, name in enumerate(
            ("project.get_summary", "task.list_open", "project.list_recent_activity"), 1
        ):
            arguments = {} if name == "project.get_summary" else {"limit": index}
            result = client.execute(context, f"direct-{index}", name, arguments)
            if result.get("tool") != name:
                raise AssertionError("unexpected direct Tool result")

        registry = ToolRegistry()
        registry.register(ListOpenTasksTool(client))
        result = AgentLoop(
            ToolThenFinalModel(),
            registry,
            repository=SQLiteAgentRuntimeRepository(Path(runtime_dir) / "runtime.sqlite3"),
        ).run("list open tasks", run_context=context)
        if result.final_answer != "remote-tool-final":
            raise AssertionError("AgentLoop remote Tool chain did not finish")
    print("P0_07_CROSS_LANGUAGE_TOOL_PASS", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
