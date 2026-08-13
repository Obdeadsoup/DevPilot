"""保存不含凭据的进程身份默认值；真实运行配置将在后续章节引入。"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ServiceIdentity:
    """描述 Python Runtime 的稳定服务身份，不代表网络服务已经启动。"""

    name: str = "devpilot-agent-service"
    contract_version: str = "agent.v1"
