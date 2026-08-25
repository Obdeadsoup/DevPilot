from collections.abc import Sequence

import grpc
import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.types import ModelResponse
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
    def abort(self, code: grpc.StatusCode, details: str) -> None:
        raise RpcAbort(code, details)


def servicer_with(script: Sequence[ModelResponse | Exception]) -> AgentRuntimeServicer:
    loop = AgentLoop(FakeModel(script), ToolRegistry())
    return AgentRuntimeServicer(AgentRuntimeApplication(loop))


def valid_request() -> agent_runtime_pb2.StartRunRequest:
    return agent_runtime_pb2.StartRunRequest(
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

