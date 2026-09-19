"""Python Agent Runtime gRPC Server 的配置与进程入口。"""

import logging
import os
from collections.abc import Callable, Mapping
from concurrent import futures
from dataclasses import dataclass, replace
from typing import Self

import grpc

from devpilot_agent_service.config import DEFAULT_RUNTIME_DB_PATH, create_runtime_repository
from devpilot_agent_service.graph.checkpoint import GraphCheckpointStore
from devpilot_agent_service.harness.demo import DemoGatewayClient
from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.harness.workflow_fake import DeterministicWorkflowModel
from devpilot_agent_service.memory.store import MemoryStore
from devpilot_agent_service.model.providers.config import OpenAICompatibleConfig
from devpilot_agent_service.model.providers.openai_compatible import OpenAICompatibleModel
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc
from devpilot_agent_service.rpc.langgraph_application import LangGraphRuntimeApplication
from devpilot_agent_service.rpc.servicer import AgentRuntimeServicer
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)
from devpilot_agent_service.runtime.cancellation import ActiveRunRegistry
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.devpilot import (
    CreateTaskTool,
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)
DEFAULT_GRPC_HOST = "0.0.0.0"
DEFAULT_GRPC_PORT = 50051
DEFAULT_MODEL_MODE = "deepseek"
FAKE_TOOL_NAMES = {
    "project.get_summary",
    "task.list_open",
    "project.list_recent_activity",
    "knowledge.search",
}
@dataclass(frozen=True, slots=True)
class RpcServerConfig:
    """Server 绑定地址与模型模式；不包含或记录 Provider Secret。"""

    host: str = DEFAULT_GRPC_HOST
    port: int = DEFAULT_GRPC_PORT
    model_mode: str = DEFAULT_MODEL_MODE
    fake_delay_seconds: float = 0.0
    runtime_db_path: str = DEFAULT_RUNTIME_DB_PATH
    fake_tool_name: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.host, str) or not self.host.strip():
            raise ValueError("AGENT_GRPC_HOST must not be blank")
        if (
            not isinstance(self.port, int)
            or isinstance(self.port, bool)
            or not 1 <= self.port <= 65535
        ):
            raise ValueError("AGENT_GRPC_PORT must be between 1 and 65535")
        if self.model_mode not in {"deepseek", "fake"}:
            raise ValueError("AGENT_MODEL_MODE must be 'deepseek' or 'fake'")
        if self.fake_delay_seconds < 0:
            raise ValueError("AGENT_FAKE_DELAY_SECONDS must not be negative")
        if not self.runtime_db_path.strip() or self.runtime_db_path == ":memory:":
            raise ValueError("AGENT_RUNTIME_DB_PATH must be a file path")
        if self.fake_tool_name is not None and self.fake_tool_name not in FAKE_TOOL_NAMES:
            raise ValueError("AGENT_FAKE_TOOL_NAME must name an allowlisted read-only Tool")
        if self.fake_tool_name is not None and self.model_mode != "fake":
            raise ValueError("AGENT_FAKE_TOOL_NAME is only valid in fake mode")

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> Self:
        source = os.environ if environ is None else environ
        raw_port = source.get("AGENT_GRPC_PORT", str(DEFAULT_GRPC_PORT))
        try:
            port = int(raw_port)
        except ValueError as error:
            raise ValueError("AGENT_GRPC_PORT must be an integer") from error
        try:
            fake_delay = float(source.get("AGENT_FAKE_DELAY_SECONDS", "0"))
        except ValueError as error:
            raise ValueError("AGENT_FAKE_DELAY_SECONDS must be numeric") from error
        fake_tool_name = source.get("AGENT_FAKE_TOOL_NAME", "").strip() or None
        return cls(
            host=source.get("AGENT_GRPC_HOST", DEFAULT_GRPC_HOST).strip(),
            port=port,
            model_mode=source.get("AGENT_MODEL_MODE", DEFAULT_MODEL_MODE).strip().lower(),
            fake_delay_seconds=fake_delay,
            runtime_db_path=source.get("AGENT_RUNTIME_DB_PATH", DEFAULT_RUNTIME_DB_PATH),
            fake_tool_name=fake_tool_name,
        )

    @property
    def bind_address(self) -> str:
        host = f"[{self.host}]" if ":" in self.host and not self.host.startswith("[") else self.host
        return f"{host}:{self.port}"


def create_application(
    config: RpcServerConfig,
    tool_client_factory: Callable[[JavaToolGatewayConfig], JavaToolGatewayClient] = (
        JavaToolGatewayClient
    ),
) -> LangGraphRuntimeApplication:
    """Build the only production AgentRun from the Graph and operational stores."""

    local_fake = (
        config.model_mode == "fake"
        and config.fake_tool_name is None
        and tool_client_factory is JavaToolGatewayClient
        and not os.environ.get("DEVPILOT_AGENT_TOOL_SERVICE_KEY")
    )
    gateway_config = None if local_fake else JavaToolGatewayConfig.from_env()
    client = DemoGatewayClient() if local_fake else tool_client_factory(gateway_config)
    close_client = getattr(client, "close", lambda: None)
    checkpoint = None
    memory = None
    try:
        if config.model_mode == "fake":
            model = DeterministicWorkflowModel(config.fake_delay_seconds, config.fake_tool_name)
            planner_model = model
            redactor = RuntimeRedactor(
                (gateway_config.service_key,) if gateway_config is not None else ()
            )
        else:
            provider_config = OpenAICompatibleConfig.from_deepseek_env()
            model = OpenAICompatibleModel(provider_config)
            planner_model = OpenAICompatibleModel(
                replace(
                    provider_config,
                    connect_timeout_seconds=min(provider_config.connect_timeout_seconds, 5.0),
                    read_timeout_seconds=min(provider_config.read_timeout_seconds, 5.0),
                    overall_timeout_seconds=min(provider_config.overall_timeout_seconds, 5.0),
                )
            )
            redactor = RuntimeRedactor((gateway_config.service_key, provider_config.api_key))
        checkpoint = GraphCheckpointStore(config.runtime_db_path + ".langgraph")
        memory = MemoryStore(config.runtime_db_path + ".memory", redactor)
        workflow = WorkflowRuntime(
            model,
            registry := _remote_tool_registry(client),
            lambda: model,
            planner_model=planner_model,
            redactor=redactor,
            checkpointer=checkpoint.saver,
            memory_store=memory,
        )
        return LangGraphRuntimeApplication(
            workflow,
            create_runtime_repository(config.runtime_db_path),
            registry,
            memory,
            close_callback=lambda: (close_client(), checkpoint.close(), memory.close()),
        )
    except Exception:
        close_client()
        if checkpoint is not None:
            checkpoint.close()
        if memory is not None:
            memory.close()
        raise


def _remote_tool_registry(client: JavaToolGatewayClient) -> ToolRegistry:
    registry = ToolRegistry()
    registry.register(ProjectSummaryTool(client))
    registry.register(ListOpenTasksTool(client))
    registry.register(RecentProjectActivityTool(client))
    registry.register(KnowledgeSearchTool(client))
    registry.register(CreateTaskTool(client))
    return registry


def create_server(
    config: RpcServerConfig,
    application: LangGraphRuntimeApplication | None = None,
) -> grpc.Server:
    """创建但不启动 Server；线程池只承载同步 Unary 调用，生命周期由进程入口管理。"""

    application = application if application is not None else create_application(config)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    agent_runtime_pb2_grpc.add_AgentRuntimeServicer_to_server(
        AgentRuntimeServicer(
            application,
            ActiveRunRegistry(),
        ),
        server,
    )
    if server.add_insecure_port(config.bind_address) == 0:
        raise RuntimeError("failed to bind Agent Runtime gRPC server")
    recovered = application.reconcile_interrupted_runs()
    if recovered:
        # 单实例启动，在接收请求前收敛旧进程记录；只记录数量，不输出外部 run_id。
        LOGGER.warning("Reconciled interrupted Agent Runtime runs count=%d", len(recovered))
    return server


def serve() -> None:
    """启动长生命周期 gRPC Server；代码生成绝不发生在服务启动阶段。"""

    logging.basicConfig(level=logging.INFO)
    config = RpcServerConfig.from_env()
    application = create_application(config)
    server = create_server(config, application)
    server.start()
    LOGGER.info(
        "Agent Runtime gRPC server started address=%s modelMode=%s fakeTool=%s",
        config.bind_address,
        config.model_mode,
        config.fake_tool_name or "disabled",
    )
    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        server.stop(grace=5).wait()
    finally:
        application.close()


if __name__ == "__main__":
    serve()
