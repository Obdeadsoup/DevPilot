import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayError,
    JavaToolGatewayFailureKind,
)
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import ModelInvocationError, ResumeRejected, RunCancelled
from devpilot_agent_service.runtime.events import RuntimeEventType
from devpilot_agent_service.runtime.persistence import RunStatus, StepStatus
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository
from devpilot_agent_service.tools.registry import ToolRegistry

CONTEXT = RunContext("run", "request")


class Crash(BaseException):
    """模拟无法执行普通异常收尾的进程中断；不强杀测试线程。"""


class RecordingTool:
    name, description, parameter_schema = "read", "read-only test", {"type": "object"}

    def __init__(self):
        self.calls = []
        self.error = None

    def execute(self, arguments, **kwargs):
        self.calls.append(kwargs["tool_call_id"])
        if self.error:
            raise self.error
        return {"value": arguments.get("value")}


def make_loop(repository, model, tool=None, **kwargs):
    registry = ToolRegistry()
    if tool:
        registry.register(tool)
    return AgentLoop(model, registry, repository=repository, **kwargs)


def calls(*ids):
    return ModelResponse.request_tools([ToolCall(i, "read", {"value": i}) for i in ids])


def retryable_failure(repository):
    model = FakeModel([ProviderError(ProviderErrorKind.TIMEOUT)])
    with pytest.raises(ModelInvocationError):
        make_loop(repository, model).run("original input", run_context=CONTEXT)
    return repository.get_latest_checkpoint("run")


def edit_checkpoint(tmp_path, transform):
    path = tmp_path / "runtime.sqlite3"
    with sqlite3.connect(path) as connection:
        row = connection.execute(
            "SELECT checkpoint_id,state_json FROM runtime_checkpoints ORDER BY checkpoint_no DESC"
        ).fetchone()
        data = json.loads(row[1])
        transform(data)
        connection.execute(
            "UPDATE runtime_checkpoints SET state_json=? WHERE checkpoint_id=?",
            (json.dumps(data), row[0]),
        )


def test_pending_cancel_and_duplicate_terminal_cancel_are_durable(repository, tmp_path):
    repository.create_run("run", "request")
    decision = repository.request_cancel("run", "request")
    assert decision.accepted and decision.run.status is RunStatus.CANCELLED
    again = SQLiteAgentRuntimeRepository(tmp_path / "runtime.sqlite3")
    assert not again.request_cancel("run", "request").accepted
    assert again.get_run("run") == decision.run
    assert again.request_cancel("run", "wrong-request") is None


def test_cancel_intent_is_idempotent_and_survives_repository_restart(repository, tmp_path):
    prepared = make_loop(repository, FakeModel([])).prepare_run("hello", run_context=CONTEXT)
    first = repository.request_cancel("run", "request")
    second = SQLiteAgentRuntimeRepository(tmp_path / "runtime.sqlite3").request_cancel(
        "run", "request"
    )
    assert first == second
    assert second.accepted and second.run.status is RunStatus.CANCEL_REQUESTED
    model = FakeModel([ModelResponse.final("unused")])
    with pytest.raises(RunCancelled):
        make_loop(repository, model).execute_prepared(prepared)
    assert model.calls == []
    assert repository.get_run("run").status is RunStatus.CANCELLED


@pytest.mark.parametrize("terminal", [RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED])
def test_cancel_never_overwrites_terminal(repository, terminal):
    repository.create_run("run", "request")
    if terminal is RunStatus.CANCELLED:
        repository.request_cancel("run", "request")
    else:
        repository.update_run_status("run", RunStatus.RUNNING)
        repository.update_run_status("run", terminal, failure_code="MODEL_ERROR")
    before = repository.get_run("run")
    assert not repository.request_cancel("run", "request").accepted
    assert repository.get_run("run") == before


def test_cancel_success_race_has_one_winner_and_no_terminal_overwrite(repository):
    with ThreadPoolExecutor(max_workers=2) as workers:
        for index in range(12):
            run_id = str(index)
            repository.create_run(run_id, "request")
            repository.update_run_status(run_id, RunStatus.RUNNING)
            barrier = threading.Barrier(2)

            def succeed():
                barrier.wait()
                return repository.compare_and_set_status(
                    run_id,
                    (RunStatus.RUNNING,),
                    RunStatus.SUCCEEDED,
                )

            def cancel():
                barrier.wait()
                return repository.request_cancel(run_id, "request").accepted

            success_future, cancel_future = workers.submit(succeed), workers.submit(cancel)
            success, cancelled = success_future.result(), cancel_future.result()
            assert success != cancelled
            if cancelled:
                assert repository.compare_and_set_status(
                    run_id,
                    (RunStatus.CANCEL_REQUESTED,),
                    RunStatus.CANCELLED,
                )
            assert repository.get_run(run_id).status in {RunStatus.SUCCEEDED, RunStatus.CANCELLED}
            assert not repository.request_cancel(run_id, "request").accepted


def test_cancel_after_model_return_prevents_tools(repository):
    class CancellingModel:
        def generate(self, messages, tools):
            repository.request_cancel("run", "request")
            return calls("one")

    tool = RecordingTool()
    with pytest.raises(RunCancelled):
        make_loop(repository, CancellingModel(), tool).run("hello", run_context=CONTEXT)
    assert tool.calls == []
    assert repository.get_run("run").status is RunStatus.CANCELLED
    assert repository.list_steps("run")[0].status is StepStatus.SUCCEEDED


def test_cancel_between_tools_preserves_success_and_stops_next_call(repository):
    class CancellingTool(RecordingTool):
        def execute(self, arguments, **kwargs):
            result = super().execute(arguments, **kwargs)
            repository.request_cancel("run", "request")
            return result

    tool = CancellingTool()
    with pytest.raises(RunCancelled):
        make_loop(repository, FakeModel([calls("one", "two")]), tool).run(
            "hello", run_context=CONTEXT
        )
    checkpoint = repository.get_latest_checkpoint("run")
    assert tool.calls == ["one"]
    assert checkpoint.state.completed_tool_call_ids == ("one",)
    assert [c.call_id for c in checkpoint.state.pending_tool_calls] == ["two"]
    assert checkpoint.state.status is RunStatus.CANCELLED
    assert repository.list_steps("run")[-1].status is StepStatus.SUCCEEDED


def test_resume_restores_messages_counters_and_continues_pending_tools(repository):
    tool = RecordingTool()
    model = FakeModel([calls("one", "two")])

    def crash_after_first_tool(event):
        if event.type is RuntimeEventType.TOOL_COMPLETED:
            raise Crash()

    with pytest.raises(Crash):
        make_loop(repository, model, tool).run(
            "original input",
            run_context=CONTEXT,
            on_event=crash_after_first_tool,
        )
    before = repository.get_latest_checkpoint("run").state
    assert before.current_step == before.tool_call_count == 1
    assert before.completed_tool_call_ids == ("one",)
    assert [c.call_id for c in before.pending_tool_calls] == ["two"]
    assert repository.reconcile_interrupted_runs() == ("run",)

    class FinalModel(FakeModel):
        def generate(self, messages, tools):
            # 恢复先直接执行 two，不能再调用 Model 重新生成 ToolCall。
            assert tool.calls == ["one", "two"]
            assert tuple(messages[: len(before.messages)]) == before.messages
            return super().generate(messages, tools)

    resumed_model = FinalModel([ModelResponse.final("done")])
    result = make_loop(repository, resumed_model, tool).resume(CONTEXT)
    assert result.final_answer == "done"
    assert len(model.calls) == len(resumed_model.calls) == 1
    state = repository.get_latest_checkpoint("run").state
    assert state.current_step == state.tool_call_count == 2
    assert state.completed_tool_call_ids == ("one", "two")
    assert [step.step_no for step in repository.list_steps("run")] == [1, 2, 3, 4]


def test_known_completed_call_in_explicit_pending_is_skipped(repository, tmp_path):
    tool = RecordingTool()

    def crash(event):
        if event.type is RuntimeEventType.TOOL_COMPLETED:
            raise Crash()

    with pytest.raises(Crash):
        make_loop(repository, FakeModel([calls("one", "two")]), tool).run(
            "hello",
            run_context=CONTEXT,
            on_event=crash,
        )
    edit_checkpoint(
        tmp_path,
        lambda data: data.update(
            pending_tool_calls=data["messages"][1]["tool_calls"],
        ),
    )
    repository.reconcile_interrupted_runs()
    make_loop(repository, FakeModel([ModelResponse.final("done")]), tool).resume(CONTEXT)
    assert tool.calls == ["one", "two"]


def test_retryable_model_failure_resumes_with_budget_not_reset(repository):
    before = retryable_failure(repository).state
    run = repository.get_run("run")
    assert run.retryable and run.failure_code == "TEMPORARY_MODEL_ERROR"
    model = FakeModel([ModelResponse.final("resumed")])
    make_loop(repository, model).resume(CONTEXT)
    assert model.calls[0].messages == before.messages
    assert repository.get_run("run").current_step == 2
    assert not repository.get_run("run").retryable


@pytest.mark.parametrize(
    "kind,retryable",
    [
        (ProviderErrorKind.TIMEOUT, True),
        (ProviderErrorKind.UNAVAILABLE, True),
        (ProviderErrorKind.RATE_LIMIT, True),
        (ProviderErrorKind.AUTH, False),
        (ProviderErrorKind.PROTOCOL, False),
        (ProviderErrorKind.UNKNOWN, False),
    ],
)
def test_only_explicit_transient_model_errors_are_retryable(repository, kind, retryable):
    with pytest.raises(ModelInvocationError):
        make_loop(repository, FakeModel([ProviderError(kind)])).run("hello", run_context=CONTEXT)
    assert repository.get_run("run").retryable is retryable
    if not retryable:
        with pytest.raises(ResumeRejected, match="RUN_NOT_RETRYABLE"):
            make_loop(repository, FakeModel([])).resume(CONTEXT)


def test_transient_tool_failure_retries_stable_id_and_consumes_another_attempt(repository):
    from devpilot_agent_service.runtime.errors import ToolExecutionError

    tool = RecordingTool()
    tool.error = JavaToolGatewayError(JavaToolGatewayFailureKind.UNAVAILABLE)
    with pytest.raises(ToolExecutionError):
        make_loop(repository, FakeModel([calls("one")]), tool).run("hello", run_context=CONTEXT)
    assert repository.get_run("run").failure_code == "TEMPORARY_TOOL_ERROR"
    tool.error = None
    make_loop(repository, FakeModel([ModelResponse.final("done")]), tool).resume(CONTEXT)
    assert tool.calls == ["one", "one"]
    assert repository.get_run("run").tool_call_count == 2
    assert repository.get_latest_checkpoint("run").state.completed_tool_call_ids == ("one",)


def test_cancelled_run_cannot_resume(repository):
    repository.create_run("run", "request")
    repository.request_cancel("run", "request")
    with pytest.raises(ResumeRejected, match="RUN_NOT_RETRYABLE"):
        make_loop(repository, FakeModel([])).resume(CONTEXT)


def test_resume_does_not_reset_exhausted_tool_attempt_budget(repository):
    from devpilot_agent_service.runtime.errors import ToolExecutionError

    tool = RecordingTool()
    tool.error = JavaToolGatewayError(JavaToolGatewayFailureKind.DEADLINE)
    with pytest.raises(ToolExecutionError):
        make_loop(repository, FakeModel([calls("one")]), tool, max_tool_calls=1).run(
            "hello", run_context=CONTEXT,
        )
    with pytest.raises(ResumeRejected, match="MAX_TOOL_CALLS_EXCEEDED"):
        make_loop(repository, FakeModel([]), tool).resume(CONTEXT)
    assert repository.get_run("run").tool_call_count == 1
    assert tool.calls == ["one"]


@pytest.mark.parametrize(
    "mutation,code",
    [
        (lambda data: data.update(version=999), "UNSUPPORTED_STATE_VERSION"),
        (lambda data: data.update(redacted=True), "REDACTED_CHECKPOINT"),
        (lambda data: data.update(current_step=-1), "INVALID_CHECKPOINT"),
        (lambda data: data.update(current_step=0), "INVALID_CHECKPOINT"),
        (lambda data: data.pop("pending_tool_calls"), "INVALID_CHECKPOINT"),
        (lambda data: data.update(next_action="TERMINAL"), "INVALID_CHECKPOINT"),
        (lambda data: data.update(max_steps=1), "MAX_STEPS_EXCEEDED"),
    ],
)
def test_invalid_checkpoint_is_rejected_without_reopening_run(repository, tmp_path, mutation, code):
    retryable_failure(repository)
    before = repository.get_run("run")
    edit_checkpoint(tmp_path, mutation)
    model = FakeModel([])
    with pytest.raises(ResumeRejected, match=code):
        make_loop(repository, model).resume(CONTEXT)
    assert repository.get_run("run") == before
    assert model.calls == []


@pytest.mark.parametrize(
    "payload,version,code",
    [
        (None, 2, "CHECKPOINT_NOT_FOUND"),
        ("broken json", 2, "INVALID_CHECKPOINT"),
        ("{}", 1, "UNSUPPORTED_STATE_VERSION"),
    ],
)
def test_missing_corrupt_or_legacy_checkpoint_never_restarts_user_input(
    repository,
    tmp_path,
    payload,
    version,
    code,
):
    retryable_failure(repository)
    with sqlite3.connect(tmp_path / "runtime.sqlite3") as connection:
        if payload is None:
            connection.execute("DELETE FROM runtime_checkpoints")
        else:
            connection.execute(
                "UPDATE runtime_checkpoints SET state_json=?,state_version=?", (payload, version)
            )
    with pytest.raises(ResumeRejected, match=code):
        make_loop(repository, FakeModel([])).resume(CONTEXT)
    assert repository.get_run("run").status is RunStatus.FAILED


def test_concurrent_resume_cannot_start_two_workers(repository):
    retryable_failure(repository)
    entered, release = threading.Event(), threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            entered.set()
            assert release.wait(3)
            return ModelResponse.final("done")

    with ThreadPoolExecutor(max_workers=1) as workers:
        future = workers.submit(make_loop(repository, BlockingModel()).resume, CONTEXT)
        try:
            assert entered.wait(2)
            with pytest.raises(ResumeRejected, match="RUN_NOT_RETRYABLE"):
                make_loop(repository, FakeModel([])).resume(CONTEXT)
        finally:
            release.set()
        assert future.result().final_answer == "done"


@pytest.mark.parametrize("phase", ["MODEL_STEP_STARTED", "TOOL_STARTED"])
def test_interrupted_attempt_is_closed_and_budget_retained_on_resume(repository, phase):
    tool = RecordingTool()

    def crash(event):
        if event.type is RuntimeEventType[phase]:
            raise Crash()

    with pytest.raises(Crash):
        make_loop(repository, FakeModel([calls("one")]), tool).run(
            "hello",
            run_context=CONTEXT,
            on_event=crash,
        )
    interrupted = repository.list_steps("run")[-1]
    assert interrupted.status is StepStatus.RUNNING
    repository.reconcile_interrupted_runs()
    assert repository.list_steps("run")[-1].status is StepStatus.FAILED
    make_loop(repository, FakeModel([ModelResponse.final("done")]), tool).resume(CONTEXT)
    if phase == "TOOL_STARTED":
        assert tool.calls == ["one"]
        assert repository.get_run("run").tool_call_count == 2
    assert repository.get_run("run").current_step == 2
