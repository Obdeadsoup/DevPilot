import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager

import grpc
import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.rpc.application import AgentRuntimeApplication
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc as rpc
from devpilot_agent_service.rpc.servicer import AgentRuntimeServicer
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.persistence import RunStatus
from devpilot_agent_service.tools.registry import ToolRegistry


@contextmanager
def connected(application):
    with ThreadPoolExecutor(max_workers=4) as executor:
        server = grpc.server(executor)
        rpc.add_AgentRuntimeServicer_to_server(AgentRuntimeServicer(application), server)
        port = server.add_insecure_port("127.0.0.1:0")
        server.start()
        channel = grpc.insecure_channel(
            f"127.0.0.1:{port}",
            options=(("grpc.enable_http_proxy", 0),),
        )
        try:
            grpc.channel_ready_future(channel).result(timeout=3)
            yield rpc.AgentRuntimeStub(channel)
        finally:
            channel.close()
            server.stop(grace=0).wait()


@pytest.mark.parametrize("streaming", [False, True])
def test_real_rpc_terminal_is_sent_after_checkpoint_commit(repository, streaming):
    application = AgentRuntimeApplication(
        AgentLoop(
            FakeModel([ModelResponse.final("done")]),
            ToolRegistry(),
            repository=repository,
        )
    )
    with connected(application) as stub:
        if streaming:
            events = list(
                stub.StreamRun(
                    pb.StreamRunRequest(run_id="run", request_id="request", user_input="hello"),
                    timeout=3,
                )
            )
            assert events[-1].type == pb.AGENT_EVENT_TYPE_RUN_SUCCEEDED
        else:
            response = stub.StartRun(
                pb.StartRunRequest(run_id="run", request_id="request", user_input="hello"),
                timeout=3,
            )
            assert response.final_output == "done"
        assert repository.get_run("run").status is RunStatus.SUCCEEDED
        assert repository.get_latest_checkpoint("run").state.status is RunStatus.SUCCEEDED
        with pytest.raises(grpc.RpcError) as duplicate:
            stub.StartRun(
                pb.StartRunRequest(run_id="run", request_id="request", user_input="again"),
                timeout=3,
            )
        assert duplicate.value.code() is grpc.StatusCode.ALREADY_EXISTS


def test_disconnected_grpc_stream_keeps_worker_and_terminal_persistence(repository):
    entered, release, completed = threading.Event(), threading.Event(), threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            entered.set()
            assert release.wait(timeout=5)
            return ModelResponse.final("finished after disconnect")

    class ObservedApplication(AgentRuntimeApplication):
        def execute_prepared(self, *args, **kwargs):
            try:
                return super().execute_prepared(*args, **kwargs)
            finally:
                completed.set()

    application = ObservedApplication(
        AgentLoop(
            BlockingModel(),
            ToolRegistry(),
            repository=repository,
        )
    )
    with connected(application) as stub:
        call = stub.StreamRun(
            pb.StreamRunRequest(
                run_id="run",
                request_id="request",
                user_input="hello",
            ),
            timeout=5,
        )
        try:
            assert next(call).type == pb.AGENT_EVENT_TYPE_RUN_STARTED
            assert entered.wait(timeout=2)
            assert repository.get_run("run").status is RunStatus.RUNNING
            call.cancel()  # 关闭 transport，未调用 CancelRun。
        finally:
            release.set()
        assert completed.wait(timeout=3)
        assert repository.get_run("run").status is RunStatus.SUCCEEDED
        checkpoint = repository.get_latest_checkpoint("run")
        assert checkpoint.state.status is RunStatus.SUCCEEDED
        assert checkpoint.state.messages[-1].content == "finished after disconnect"
