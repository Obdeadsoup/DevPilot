"""Java Resume smoke 的独立 Python Server；暂时性首错由测试 Model 提供。"""

import os
from pathlib import Path

from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.server import RpcServerConfig, create_server
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository
from devpilot_agent_service.tools.registry import ToolRegistry


class FailOnceModel:
    def __init__(self):
        self.failed = False

    def generate(self, messages, tools):
        if not self.failed:
            self.failed = True
            raise ProviderError(ProviderErrorKind.TIMEOUT)
        users = [m.content for m in messages if m.role is MessageRole.USER]
        if users != ["original-input"]:
            raise AssertionError("resume did not preserve the original context")
        return ModelResponse.final("resumed:" + users[0])


def main():
    # 路径由 Java @TempDir 拥有；子进程被测试框架终止后也能由父进程清理。
    path = Path(os.environ["AGENT_RUNTIME_DB_PATH"])
    application = AgentRuntimeApplication(
        AgentLoop(
            FailOnceModel(),
            ToolRegistry(),
            repository=SQLiteAgentRuntimeRepository(path),
        )
    )
    config = RpcServerConfig(
        host="127.0.0.1",
        port=int(os.environ["AGENT_GRPC_PORT"]),
        model_mode="fake",
        runtime_db_path=str(path),
    )
    server = create_server(config, application)
    server.start()
    try:
        server.wait_for_termination()
    finally:
        server.stop(0).wait()


if __name__ == "__main__":
    main()
