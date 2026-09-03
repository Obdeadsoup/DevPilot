"""服务身份和 Runtime Store 组合根；凭据仅用于持久化脱敏。"""

import os
from dataclasses import dataclass

from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.runtime.repository import AgentRuntimeRepository
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository

DEFAULT_RUNTIME_DB_PATH = ".runtime/agent-runtime.sqlite3"


def create_runtime_repository(path: str | None = None) -> AgentRuntimeRepository:
    return SQLiteAgentRuntimeRepository(
        path
        if path is not None
        else os.environ.get("AGENT_RUNTIME_DB_PATH", DEFAULT_RUNTIME_DB_PATH),
        redactor=RuntimeRedactor(
            [
                os.environ.get("DEEPSEEK_API_KEY", ""),
                os.environ.get("DEVPILOT_AGENT_TOOL_SERVICE_KEY", ""),
            ]
        ),
    )


@dataclass(frozen=True, slots=True)
class ServiceIdentity:
    """描述 Python Runtime 的稳定服务身份，不代表网络服务已经启动。"""

    name: str = "devpilot-agent-service"
    contract_version: str = "agent.v1"
