import threading
import time
from collections.abc import Sequence

import grpc
import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2
from devpilot_agent_service.rpc.servicer import AgentRuntimeServicer
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.tools.registry import ToolRegistry


class RpcAbort(Exception):
    def __init__(self, code: grpc.StatusCode, details: str) -> None:
        super().__init__(details)
        self.code = code
        self.details = details


class FakeServicerContext:
    def __init__(self) -> None:
        self.active = True

    def abort(self, code: grpc.StatusCode, details: str) -> None:
        raise RpcAbort(code, details)

    def is_active(self) -> bool:
        return self.active


def servicer_with(script: Sequence[ModelResponse | Exception]) -> AgentRuntimeServicer:
    loop = AgentLoop(FakeModel(script), ToolRegistry())
    return AgentRuntimeServicer(AgentRuntimeApplication(loop))


def valid_request() -> agent_runtime_pb2.StartRunRequest:
    return agent_runtime_pb2.StartRunRequest(
        request_id="request-1",
        run_id="run-1",
        user_input="hello",
    )


def valid_stream_request() -> agent_runtime_pb2.StreamRunRequest:
    return agent_runtime_pb2.StreamRunRequest(
        request_id="request-1",
        run_id="run-1",
        user_input="hello",
    )


def test_start_run_returns_same_run_id_and_final_output() -> None:
    response = servicer_with([ModelResponse.final("finished")]).StartRun(
        valid_request(),
        FakeServicerContext(),  # type: ignore[arg-type]
    )

    assert response.run_id == "run-1"
    assert response.final_output == "finished"
    assert response.status == agent_runtime_pb2.RUN_STATUS_SUCCEEDED


@pytest.mark.parametrize("field_name", ["request_id", "run_id", "user_input"])
def test_start_run_rejects_blank_required_field(field_name: str) -> None:
    request = valid_request()
    setattr(request, field_name, " ")

    with pytest.raises(RpcAbort) as captured:
        servicer_with([ModelResponse.final("unused")]).StartRun(
            request,
            FakeServicerContext(),  # type: ignore[arg-type]
        )

    assert captured.value.code is grpc.StatusCode.INVALID_ARGUMENT
    assert captured.value.details == f"{field_name} must not be blank"


def test_agent_loop_failure_becomes_sanitized_internal_status() -> None:
    with pytest.raises(RpcAbort) as captured:
        servicer_with([RuntimeError("provider-body-must-not-leak")]).StartRun(
            valid_request(),
            FakeServicerContext(),  # type: ignore[arg-type]
        )

    assert captured.value.code is grpc.StatusCode.INTERNAL
    assert captured.value.details == "agent runtime failed"
    assert "provider-body" not in str(captured.value)


@pytest.mark.parametrize("field_name", ["request_id", "run_id", "user_input"])
def test_stream_run_rejects_blank_required_field(field_name: str) -> None:
    request = valid_stream_request()
    setattr(request, field_name, " ")

    with pytest.raises(RpcAbort) as captured:
        list(
            servicer_with([ModelResponse.final("unused")]).StreamRun(
                request,
                FakeServicerContext(),  # type: ignore[arg-type]
            )
        )

    assert captured.value.code is grpc.StatusCode.INVALID_ARGUMENT
    assert captured.value.details == f"{field_name} must not be blank"


def test_stream_run_emits_strict_sequence_lifecycle_and_terminal_last() -> None:
    model_script = [
        ModelResponse.request_tools(
            [ToolCall(call_id="private-id", name="echo", arguments={"text": "secret"})]
        ),
        ModelResponse.final("finished"),
    ]
    registry = ToolRegistry()
    from devpilot_agent_service.tools.echo import EchoTool

    registry.register(EchoTool())
    servicer = AgentRuntimeServicer(
        AgentRuntimeApplication(AgentLoop(FakeModel(model_script), registry))
    )

    events = list(servicer.StreamRun(valid_stream_request(), FakeServicerContext()))  # type: ignore[arg-type]

    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert [event.event_id for event in events] == [
        f"run-1:{sequence}" for sequence in range(1, len(events) + 1)
    ]
    assert [event.type for event in events] == [
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_TOOL_STARTED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_TOOL_COMPLETED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
    ]
    assert events[2].tool_name == "echo"
    assert events[-1].final_output == "finished"
    assert "private-id" not in repr(events)
    assert "secret" not in repr(events)


def test_stream_run_business_failure_is_single_last_terminal() -> None:
    events = list(
        servicer_with([RuntimeError("private provider body")]).StreamRun(
            valid_stream_request(),
            FakeServicerContext(),  # type: ignore[arg-type]
        )
    )

    assert events[0].type == agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED
    assert events[-1].type == agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_FAILED
    assert events[-1].failure_kind == "MODEL_ERROR"
    assert sum(
        event.type
        in {
            agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
            agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_FAILED,
        }
        for event in events
    ) == 1


def test_active_run_rejects_duplicate_and_cancel_emits_exactly_one_terminal() -> None:
    release_model = threading.Event()

    class BlockingModel:
        def generate(self, messages: object, tools: object) -> ModelResponse:
            assert release_model.wait(timeout=2)
            return ModelResponse.final("must-not-succeed")

    servicer = AgentRuntimeServicer(
        AgentRuntimeApplication(AgentLoop(BlockingModel(), ToolRegistry()))
    )
    context = FakeServicerContext()
    stream = servicer.StreamRun(valid_stream_request(), context)  # type: ignore[arg-type]
    first = next(stream)
    assert first.type == agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED

    duplicate = servicer.StreamRun(
        valid_stream_request(), FakeServicerContext()  # type: ignore[arg-type]
    )
    with pytest.raises(RpcAbort) as captured:
        next(duplicate)
    assert captured.value.code is grpc.StatusCode.ALREADY_EXISTS

    cancel_request = agent_runtime_pb2.CancelRunRequest(
        run_id="run-1", request_id="request-1"
    )
    assert servicer.CancelRun(cancel_request, context).status == (
        agent_runtime_pb2.CANCEL_RUN_STATUS_ACCEPTED
    )
    assert servicer.CancelRun(cancel_request, context).status == (
        agent_runtime_pb2.CANCEL_RUN_STATUS_ACCEPTED
    )
    release_model.set()
    events = [first, *list(stream)]

    terminal_types = {
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_FAILED,
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_CANCELLED,
    }
    assert [event.type for event in events if event.type in terminal_types] == [
        agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_CANCELLED
    ]
    assert servicer.CancelRun(cancel_request, context).status == (
        agent_runtime_pb2.CANCEL_RUN_STATUS_ALREADY_TERMINAL
    )


def test_cancelled_context_releases_producer_blocked_by_bounded_queue(monkeypatch) -> None:
    import devpilot_agent_service.rpc.servicer as servicer_module

    monkeypatch.setattr(servicer_module, "STREAM_QUEUE_CAPACITY", 1)
    context = FakeServicerContext()
    generator = servicer_with(
        [
            ModelResponse.request_tools(
                [ToolCall(call_id="call-1", name="missing", arguments={})]
            )
        ]
    ).StreamRun(valid_stream_request(), context)  # type: ignore[arg-type]

    assert next(generator).type == agent_runtime_pb2.AGENT_EVENT_TYPE_RUN_STARTED
    time.sleep(0.2)
    context.active = False
    stopped = threading.Event()

    def finish_generator() -> None:
        list(generator)
        stopped.set()

    thread = threading.Thread(target=finish_generator)
    thread.start()
    thread.join(timeout=1)
    assert stopped.is_set()
