"""AgentRuntime gRPC 入站边界。"""

import logging
import queue
import threading
from collections.abc import Iterator
from dataclasses import dataclass

import grpc

from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2, agent_runtime_pb2_grpc
from devpilot_agent_service.runtime.cancellation import (
    ActiveRunRegistry,
    CancelStatus,
    DuplicateActiveRunError,
)
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import AgentRuntimeError, RunCancelled
from devpilot_agent_service.runtime.events import RuntimeEvent, RuntimeEventType

LOGGER = logging.getLogger(__name__)
STREAM_QUEUE_CAPACITY = 64
QUEUE_POLL_SECONDS = 0.1


@dataclass(frozen=True, slots=True)
class _StreamOutcome:
    final_output: str | None = None
    failure_kind: str | None = None
    cancelled: bool = False


class AgentRuntimeServicer(agent_runtime_pb2_grpc.AgentRuntimeServicer):
    """校验 protobuf 请求、调用应用门面，并只返回脱敏 gRPC Status。"""

    def __init__(
        self,
        application: AgentRuntimeApplication,
        active_runs: ActiveRunRegistry | None = None,
    ) -> None:
        self._application = application
        self._active_runs = active_runs or ActiveRunRegistry()

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
            token = self._active_runs.register(request.run_id, request.request_id)
        except DuplicateActiveRunError:
            context.abort(grpc.StatusCode.ALREADY_EXISTS, "agent run is already active")
        try:
            result = self._application.start_run(
                request.user_input,
                run_context=RunContext(request.run_id, request.request_id),
                cancellation_token=token,
            )
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
        finally:
            self._active_runs.complete(request.run_id)

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
        """用有界 Queue 把同步 AgentLoop 桥接为 Server Streaming 生命周期事件。"""

        _require_non_blank(request.request_id, "request_id", context)
        _require_non_blank(request.run_id, "run_id", context)
        _require_non_blank(request.user_input, "user_input", context)

        events: queue.Queue[RuntimeEvent | _StreamOutcome] = queue.Queue(
            maxsize=STREAM_QUEUE_CAPACITY
        )
        try:
            token = self._active_runs.register(request.run_id, request.request_id)
        except DuplicateActiveRunError:
            context.abort(grpc.StatusCode.ALREADY_EXISTS, "agent run is already active")

        def enqueue(item: RuntimeEvent | _StreamOutcome) -> None:
            # 客户端断开不取消 AgentLoop；停止普通投递可避免 producer 永久堵塞。
            while context.is_active():
                try:
                    events.put(item, timeout=QUEUE_POLL_SECONDS)
                    return
                except queue.Full:
                    continue

        def run_worker() -> None:
            try:
                result = self._application.start_run(
                    request.user_input,
                    run_context=RunContext(request.run_id, request.request_id),
                    on_event=enqueue,
                    cancellation_token=token,
                )
                enqueue(_StreamOutcome(final_output=result.final_answer))
            except RunCancelled:
                enqueue(_StreamOutcome(cancelled=True))
            except AgentRuntimeError as error:
                LOGGER.warning(
                    "Agent stream failed failureType=%s stopReason=%s",
                    type(error).__name__,
                    error.stop_reason.value,
                )
                enqueue(_StreamOutcome(failure_kind=error.stop_reason.name))
            except Exception as error:
                # failure_kind 固定且脱敏；不把 Provider body、Tool 参数或堆栈流给 Java。
                LOGGER.error("Agent stream failed failureType=%s", type(error).__name__)
                enqueue(_StreamOutcome(failure_kind="INTERNAL"))
            finally:
                # 客户端断流不等于 worker 已结束；直到真实执行退出才允许同 run_id 再注册。
                self._active_runs.complete(request.run_id)

        worker = threading.Thread(
            target=run_worker,
            name=f"agent-run-{request.run_id[:12]}",
            daemon=True,
        )
        worker.start()

        sequence = 1
        yield _agent_event(
            request.run_id,
            sequence,
            agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED,
        )
        sequence += 1
        while context.is_active():
            try:
                item = events.get(timeout=QUEUE_POLL_SECONDS)
            except queue.Empty:
                continue
            if isinstance(item, _StreamOutcome):
                if item.cancelled:
                    yield _agent_event(
                        request.run_id,
                        sequence,
                        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_CANCELLED,
                    )
                elif item.failure_kind is not None:
                    yield _agent_event(
                        request.run_id,
                        sequence,
                        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_FAILED,
                        failure_kind=item.failure_kind,
                    )
                else:
                    yield _agent_event(
                        request.run_id,
                        sequence,
                        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
                        final_output=item.final_output or "",
                    )
                return
            yield _runtime_event(request.run_id, sequence, item)
            sequence += 1

    def CancelRun(
        self,
        request: agent_runtime_pb2.CancelRunRequest,
        context: grpc.ServicerContext,
    ) -> agent_runtime_pb2.CancelRunResponse:
        """设置协作式 token；重复 active 取消仍返回 ACCEPTED。"""

        _require_non_blank(request.run_id, "run_id", context)
        _require_non_blank(request.request_id, "request_id", context)
        status = self._active_runs.cancel(request.run_id, request.request_id)
        proto_status = {
            CancelStatus.ACCEPTED: agent_runtime_pb2.CANCEL_RUN_STATUS_ACCEPTED,
            CancelStatus.NOT_FOUND: agent_runtime_pb2.CANCEL_RUN_STATUS_NOT_FOUND,
            CancelStatus.ALREADY_TERMINAL:
                agent_runtime_pb2.CANCEL_RUN_STATUS_ALREADY_TERMINAL,
        }[status]
        return agent_runtime_pb2.CancelRunResponse(
            accepted=status is CancelStatus.ACCEPTED,
            status=proto_status,
        )


def _require_non_blank(
    value: str,
    field_name: str,
    context: grpc.ServicerContext,
) -> None:
    if not value.strip():
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, f"{field_name} must not be blank")


def _runtime_event(
    run_id: str,
    sequence: int,
    event: RuntimeEvent,
) -> agent_runtime_pb2.AgentEvent:
    event_type = {
        RuntimeEventType.MODEL_STEP_STARTED:
            agent_runtime_pb2.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
        RuntimeEventType.TOOL_STARTED: agent_runtime_pb2.AGENT_EVENT_TYPE_TOOL_STARTED,
        RuntimeEventType.TOOL_COMPLETED: agent_runtime_pb2.AGENT_EVENT_TYPE_TOOL_COMPLETED,
    }[event.type]
    return _agent_event(
        run_id,
        sequence,
        event_type,
        step=event.step,
        tool_name=event.tool_name or "",
    )


def _agent_event(
    run_id: str,
    sequence: int,
    event_type: int,
    *,
    step: int = 0,
    tool_name: str = "",
    final_output: str = "",
    failure_kind: str = "",
) -> agent_runtime_pb2.AgentEvent:
    return agent_runtime_pb2.AgentEvent(
        event_id=f"{run_id}:{sequence}",
        run_id=run_id,
        sequence=sequence,
        type=event_type,
        step=step,
        tool_name=tool_name,
        final_output=final_output,
        failure_kind=failure_kind,
    )
