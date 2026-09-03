import threading
import time

import grpc
import pytest
from fakes.fake_model import FakeModel
from test_cancel_resume import CONTEXT, RecordingTool, calls, make_loop
from test_rpc_servicer import FakeServicerContext
from test_runtime_stream_persistence import connected

from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.servicer import AgentRuntimeServicer
from devpilot_agent_service.runtime.errors import ModelInvocationError
from devpilot_agent_service.runtime.persistence import RunStatus
from devpilot_agent_service.runtime.repository import RuntimeRepositoryError


def test_resume_rpc_returns_existing_run_and_explicit_rejection_errors(repository):
    model = FakeModel([ProviderError(ProviderErrorKind.TIMEOUT), ModelResponse.final("resumed")])
    application = AgentRuntimeApplication(make_loop(repository, model))
    with connected(application) as stub:
        events = list(
            stub.StreamRun(
                pb.StreamRunRequest(
                    run_id="run",
                    request_id="request",
                    user_input="original request",
                ),
                timeout=3,
            )
        )
        assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_FAILED
        assert events[-1].failure_kind == "MODEL_ERROR"  # 旧 Java 分类继续兼容。
        assert repository.get_run("run").retryable
        with pytest.raises(grpc.RpcError) as wrong_identity:
            list(stub.ResumeRun(pb.ResumeRunRequest(run_id="run", request_id="wrong"), timeout=3))
        assert wrong_identity.value.code() is grpc.StatusCode.NOT_FOUND
        resumed = list(
            stub.ResumeRun(pb.ResumeRunRequest(run_id="run", request_id="request"), timeout=3)
        )
        assert [e.type for e in resumed] == [
            pb.AGENT_EVENT_TYPE_RUN_STARTED,
            pb.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
            pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED,
        ]
        assert resumed[1].step == 2
        assert resumed[-1].final_output == "resumed"
        assert all(e.run_id == "run" for e in resumed)
        assert model.calls[1].messages == model.calls[0].messages
        with pytest.raises(grpc.RpcError) as terminal:
            list(stub.ResumeRun(pb.ResumeRunRequest(run_id="run", request_id="request"), timeout=3))
        assert terminal.value.code() is grpc.StatusCode.FAILED_PRECONDITION
        assert terminal.value.details() == "RUN_NOT_RETRYABLE"


def test_cancel_during_blocking_model_persists_intent_before_return_and_survives_new_servicer(
    repository,
):
    entered, release = threading.Event(), threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            entered.set()
            assert release.wait(5)
            return ModelResponse.final("must not win after cancel")

    application = AgentRuntimeApplication(make_loop(repository, BlockingModel()))
    request = pb.CancelRunRequest(run_id="run", request_id="request")
    with connected(application) as stub:
        stream = stub.StreamRun(
            pb.StreamRunRequest(
                run_id="run",
                request_id="request",
                user_input="hello",
            ),
            timeout=5,
        )
        try:
            assert next(stream).type == pb.AGENT_EVENT_TYPE_RUN_STARTED
            assert entered.wait(2)
            cancelled = stub.CancelRun(request, timeout=2)
            assert cancelled.accepted and cancelled.runtime_status == "CANCEL_REQUESTED"
            assert repository.get_run("run").status is RunStatus.CANCEL_REQUESTED
            assert stub.CancelRun(request, timeout=2).runtime_status == "CANCEL_REQUESTED"
        finally:
            release.set()
        events = list(stream)
        assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_CANCELLED
        assert repository.get_run("run").status is RunStatus.CANCELLED
    with connected(application) as new_stub:
        # 新 Servicer 没有进程内 terminal tombstone，仍从持久记录返回终态。
        terminal = new_stub.CancelRun(request, timeout=2)
        assert terminal.status == pb.CANCEL_RUN_STATUS_ALREADY_TERMINAL
        assert terminal.runtime_status == "CANCELLED"


def test_failed_cancel_write_does_not_signal_worker(repository, monkeypatch):
    entered, release = threading.Event(), threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            entered.set()
            assert release.wait(5)
            return ModelResponse.final("success")

    application = AgentRuntimeApplication(make_loop(repository, BlockingModel()))
    with connected(application) as stub:
        stream = stub.StreamRun(
            pb.StreamRunRequest(
                run_id="run",
                request_id="request",
                user_input="hello",
            ),
            timeout=5,
        )
        try:
            next(stream)
            assert entered.wait(2)

            def unavailable(*args):
                raise RuntimeRepositoryError("injected failure")

            with monkeypatch.context() as patch:
                patch.setattr(repository, "request_cancel", unavailable)
                with pytest.raises(grpc.RpcError) as failed:
                    stub.CancelRun(
                        pb.CancelRunRequest(run_id="run", request_id="request"), timeout=2
                    )
                assert failed.value.code() is grpc.StatusCode.INTERNAL
                assert repository.get_run("run").status is RunStatus.RUNNING
        finally:
            release.set()
        assert list(stream)[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED


def test_resume_stream_disconnect_does_not_cancel_execution(repository):
    with pytest.raises(ModelInvocationError):
        make_loop(repository, FakeModel([ProviderError(ProviderErrorKind.TIMEOUT)])).run(
            "hello",
            run_context=CONTEXT,
        )
    entered, release, completed = threading.Event(), threading.Event(), threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            entered.set()
            assert release.wait(5)
            return ModelResponse.final("done")

    class ObservedApplication(AgentRuntimeApplication):
        def execute_prepared(self, *args, **kwargs):
            try:
                return super().execute_prepared(*args, **kwargs)
            finally:
                completed.set()

    application = ObservedApplication(make_loop(repository, BlockingModel()))
    with connected(application) as stub:
        stream = stub.ResumeRun(pb.ResumeRunRequest(run_id="run", request_id="request"), timeout=5)
        try:
            next(stream)
            assert entered.wait(2)
            stream.cancel()
        finally:
            release.set()
        assert completed.wait(3)
        assert repository.get_run("run").status is RunStatus.SUCCEEDED
        assert repository.get_latest_checkpoint("run").state.current_step == 2


def test_cancel_can_pass_a_full_lifecycle_event_queue(repository, monkeypatch):
    import devpilot_agent_service.rpc.servicer as module

    monkeypatch.setattr(module, "STREAM_QUEUE_CAPACITY", 1)
    tool = RecordingTool()
    application = AgentRuntimeApplication(make_loop(repository, FakeModel([calls("one")]), tool))
    servicer = AgentRuntimeServicer(application)
    context = FakeServicerContext()
    stream = servicer.StreamRun(
        pb.StreamRunRequest(
            run_id="run",
            request_id="request",
            user_input="hello",
        ),
        context,
    )
    try:
        next(stream)  # 不消费后续生命周期事件，让 worker 在 TOOL_STARTED 遇到满队列。
        deadline = time.monotonic() + 3
        while len(repository.list_steps("run")) < 2 and time.monotonic() < deadline:
            time.sleep(0.01)
        assert len(repository.list_steps("run")) == 2
        assert servicer.CancelRun(
            pb.CancelRunRequest(run_id="run", request_id="request"), context
        ).accepted
        while (
            repository.get_run("run").status is not RunStatus.CANCELLED
            and time.monotonic() < deadline
        ):
            time.sleep(0.01)
        assert repository.get_run("run").status is RunStatus.CANCELLED
        assert tool.calls == []
    finally:
        context.active = False
        list(stream)
