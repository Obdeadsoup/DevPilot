"""AgentRuntime gRPC 入站边界。"""

import logging
from collections.abc import Iterator

import grpc

from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2, agent_runtime_pb2_grpc
from devpilot_agent_service.runtime.errors import AgentRuntimeError

LOGGER = logging.getLogger(__name__)


class AgentRuntimeServicer(agent_runtime_pb2_grpc.AgentRuntimeServicer):
    """校验 protobuf 请求、调用应用门面，并只返回脱敏 gRPC Status。"""

    def __init__(self, application: AgentRuntimeApplication) -> None:
        self._application = application

    def StartRun(
        self,
        request: agent_runtime_pb2.StartRunRequest,
        context: grpc.ServicerContext,
    ) -> agent_runtime_pb2.StartRunResponse:
        """执行同步 Unary StartRun；空字段在进入 AgentLoop 前即被拒绝。"""

        _require_non_blank(request.request_id, "request_id", context)
        _require_non_blank(request.run_id, "run_id", context)
        _require_non_blank(request.user_input, "user_input", context)

        try:
            result = self._application.start_run(request.user_input)
        except AgentRuntimeError as error:
            LOGGER.warning(
                "Agent runtime failed failureType=%s stopReason=%s",
                type(error).__name__,
                error.stop_reason.value,
            )
            context.abort(grpc.StatusCode.INTERNAL, "agent runtime failed")
        except Exception as error:
            # 不打印异常文本或堆栈，避免 Provider body、Tool 参数或 Secret 进入服务日志。
            LOGGER.error("Agent runtime failed failureType=%s", type(error).__name__)
            context.abort(grpc.StatusCode.INTERNAL, "agent runtime failed")

        return agent_runtime_pb2.StartRunResponse(
            run_id=request.run_id,
            final_output=result.final_answer,
            status=agent_runtime_pb2.RUN_STATUS_SUCCEEDED,
        )

    def StreamRun(
        self,
        request: agent_runtime_pb2.StreamRunRequest,
        context: grpc.ServicerContext,
    ) -> Iterator[agent_runtime_pb2.AgentEvent]:
        """本章不开放流式事件，显式返回稳定的 UNIMPLEMENTED。"""

        context.abort(grpc.StatusCode.UNIMPLEMENTED, "StreamRun is not implemented")

    def CancelRun(
        self,
        request: agent_runtime_pb2.CancelRunRequest,
        context: grpc.ServicerContext,
    ) -> agent_runtime_pb2.CancelRunResponse:
        """本章不开放取消语义，显式返回稳定的 UNIMPLEMENTED。"""

        context.abort(grpc.StatusCode.UNIMPLEMENTED, "CancelRun is not implemented")


def _require_non_blank(
    value: str,
    field_name: str,
    context: grpc.ServicerContext,
) -> None:
    if not value.strip():
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, f"{field_name} must not be blank")
