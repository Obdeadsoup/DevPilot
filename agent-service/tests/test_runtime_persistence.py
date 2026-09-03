import json
import sqlite3
import threading

import pytest
from fakes.fake_model import FakeModel

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.agent_loop import AgentLoop
from devpilot_agent_service.runtime.cancellation import CancellationToken
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import (
    DuplicateToolCallIdError,
    InvalidModelResponseError,
    MaxStepsExceeded,
    MaxToolCallsExceeded,
    ModelInvocationError,
    RunCancelled,
    ToolExecutionError,
    UnknownToolError,
)
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.runtime.persistence import RunStatus, RuntimeCheckpointState, StepStatus
from devpilot_agent_service.runtime.repository import RunAlreadyExists, RuntimeRepositoryError
from devpilot_agent_service.tools.echo import EchoTool
from devpilot_agent_service.tools.registry import ToolRegistry

CONTEXT = RunContext("run", "request")


def loop(repository, script, **kwargs):
    registry = ToolRegistry()
    registry.register(EchoTool())
    return AgentLoop(FakeModel(script), registry, repository=repository, **kwargs)


def request(*ids):
    return ModelResponse.request_tools([ToolCall(i, "echo", {"text": i}) for i in ids])


def snapshots(tmp_path):
    with sqlite3.connect(tmp_path / "runtime.sqlite3") as connection:
        return [
            RuntimeCheckpointState.from_json(row[0])
            for row in connection.execute(
                "SELECT state_json FROM runtime_checkpoints ORDER BY checkpoint_no",
            )
        ]


def test_final_answer_persists_one_model_step_and_terminal_checkpoint(repository):
    result = loop(repository, [ModelResponse.final("done")]).run("hello")
    run = repository.get_run(result.run_id)
    steps = repository.list_steps(result.run_id)
    checkpoint = repository.get_latest_checkpoint(result.run_id)
    assert run.status is RunStatus.SUCCEEDED
    assert run.current_step == 1
    assert len(steps) == 1
    assert steps[0].step_type == "MODEL_CALL"
    assert steps[0].status is StepStatus.SUCCEEDED
    assert checkpoint.after_step == 1
    assert checkpoint.checkpoint_no == 4  # initial / started / model result / finalization
    assert checkpoint.state.messages == result.messages
    assert checkpoint.state.status is RunStatus.SUCCEEDED
    assert checkpoint.state.next_action == "TERMINAL"


def test_one_tool_has_three_boundaries_and_reconstructable_protocol(repository, tmp_path):
    result = loop(repository, [request("one"), ModelResponse.final("done")]).run(
        "hello",
        run_context=CONTEXT,
    )
    steps = repository.list_steps("run")
    states = snapshots(tmp_path)
    assert [step.step_type for step in steps] == ["MODEL_CALL", "TOOL_CALL", "MODEL_CALL"]
    assert [step.step_no for step in steps] == [1, 2, 3]
    assert all(step.status is StepStatus.SUCCEEDED for step in steps)
    assert [state.next_action for state in states] == [
        "MODEL",
        "MODEL",
        "TOOLS",
        "TOOLS",
        "MODEL",
        "MODEL",
        "FINALIZE",
        "TERMINAL",
    ]
    assert [state.current_step for state in states] == [0, 1, 1, 1, 1, 2, 2, 2]
    assert [state.tool_call_count for state in states] == [0, 0, 0, 1, 1, 1, 1, 1]
    assert states[2].messages[-1].tool_calls[0].call_id == "one"
    assert states[4].completed_tool_call_ids == ("one",)
    assert states[-1].messages == result.messages
    assert states[-1].request_id == "request"
    assert not states[-1].redacted


def test_partial_batch_remembers_completed_and_pending_calls(repository, tmp_path):
    loop(repository, [request("one", "two"), ModelResponse.final("done")]).run(
        "hello",
        run_context=CONTEXT,
    )
    states = snapshots(tmp_path)
    partial = next(s for s in states if s.completed_tool_call_ids == ("one",))
    assert partial.next_action == "TOOLS"
    assert partial.completed_tool_call_ids == ("one",)
    assert [call.call_id for call in partial.messages[1].tool_calls] == ["one", "two"]
    assert states[-1].tool_call_count == 2


@pytest.mark.parametrize(
    "script,exception,types,code",
    [
        (
            [RuntimeError("secret raw provider response")],
            ModelInvocationError,
            ["MODEL_CALL"],
            "MODEL_ERROR",
        ),
        ([object()], InvalidModelResponseError, ["MODEL_CALL"], "INVALID_TOOL_CALL"),
        (
            [ModelResponse.request_tools([ToolCall("one", "missing", {})])],
            UnknownToolError,
            ["MODEL_CALL", "TOOL_CALL"],
            "INVALID_TOOL_CALL",
        ),
    ],
)
def test_failures_persist_stable_classification(repository, script, exception, types, code):
    with pytest.raises(exception):
        loop(repository, script).run("hello", run_context=CONTEXT)
    steps = repository.list_steps("run")
    assert [step.step_type for step in steps] == types
    assert steps[-1].status is StepStatus.FAILED
    assert steps[-1].error == {"code": code}
    run = repository.get_run("run")
    assert run.status is RunStatus.FAILED
    assert run.failure_code == code
    assert run.failure_message == "runtime execution failed"
    assert repository.get_latest_checkpoint("run").state.status is RunStatus.FAILED
    assert "raw provider response" not in repr(steps)


def test_tool_exception_remains_fatal_and_is_not_marked_completed(repository):
    class ExplodingTool:
        name, description, parameter_schema = "echo", "fails", {"type": "object"}

        def execute(self, arguments, **kwargs):
            raise RuntimeError("private tool body")

    registry = ToolRegistry()
    registry.register(ExplodingTool())
    model = FakeModel([request("one"), ModelResponse.final("unused")])
    with pytest.raises(ToolExecutionError):
        AgentLoop(model, registry, repository=repository).run("hello", run_context=CONTEXT)
    assert len(model.calls) == 1
    assert repository.list_steps("run")[-1].status is StepStatus.FAILED
    checkpoint = repository.get_latest_checkpoint("run")
    assert checkpoint.state.completed_tool_call_ids == ()
    assert checkpoint.state.tool_call_count == 1
    assert repository.get_run("run").failure_code == "TOOL_ERROR"


@pytest.mark.parametrize(
    "script,limits,exception,tool_count",
    [
        ([request("same", "same")], {}, DuplicateToolCallIdError, 0),
        ([request("same"), request("same")], {}, DuplicateToolCallIdError, 1),
        ([request("one", "two")], {"max_tool_calls": 1}, MaxToolCallsExceeded, 0),
        ([request("one")], {"max_steps": 1}, MaxStepsExceeded, 1),
    ],
)
def test_guards_keep_original_batch_and_budget_semantics(
    repository,
    script,
    limits,
    exception,
    tool_count,
):
    with pytest.raises(exception):
        loop(repository, script, **limits).run("hello", run_context=CONTEXT)
    assert sum(s.step_type == "TOOL_CALL" for s in repository.list_steps("run")) == tool_count
    run = repository.get_run("run")
    assert run.status is RunStatus.FAILED
    assert run.tool_call_count == tool_count
    assert repository.get_latest_checkpoint("run").state.status is RunStatus.FAILED


def test_preexisting_run_is_never_reexecuted(repository):
    loop(repository, [ModelResponse.final("done")]).run("hello", run_context=CONTEXT)
    model = FakeModel([ModelResponse.final("must not execute")])
    with pytest.raises(RunAlreadyExists):
        AgentLoop(model, ToolRegistry(), repository=repository).run("again", run_context=CONTEXT)
    assert not model.calls
    assert repository.get_run("run").status is RunStatus.SUCCEEDED
    assert len(repository.list_steps("run")) == 1


def test_model_is_called_after_step_commits_without_holding_database_lock(repository):
    observed = []

    class InspectingModel:
        def generate(self, messages, tools):
            def read_from_other_worker():
                observed.append(repository.list_steps("run")[0].status)

            thread = threading.Thread(target=read_from_other_worker)
            thread.start()
            thread.join(timeout=2)
            assert not thread.is_alive()
            return ModelResponse.final("done")

    AgentLoop(InspectingModel(), ToolRegistry(), repository=repository).run(
        "hello",
        run_context=CONTEXT,
    )
    assert observed == [StepStatus.RUNNING]


def test_existing_cancel_after_tool_preserves_completed_fact(repository):
    token = CancellationToken()

    class CancellingEcho(EchoTool):
        def execute(self, arguments, **kwargs):
            token.cancel()
            return super().execute(arguments, **kwargs)

    registry = ToolRegistry()
    registry.register(CancellingEcho())
    with pytest.raises(RunCancelled):
        AgentLoop(FakeModel([request("one")]), registry, repository=repository).run(
            "hello",
            run_context=CONTEXT,
            cancellation_token=token,
        )
    checkpoint = repository.get_latest_checkpoint("run")
    assert checkpoint.state.status is RunStatus.CANCELLED
    assert checkpoint.state.completed_tool_call_ids == ("one",)
    assert checkpoint.state.messages[-1].role is MessageRole.TOOL
    assert repository.list_steps("run")[-1].status is StepStatus.SUCCEEDED
    assert repository.get_run("run").failure_code is None


def test_checkpoint_failure_rolls_back_success_and_never_returns_final(repository, monkeypatch):
    save = repository.save_checkpoint

    def fail_success(run_id, after_step, state):
        if state.status is RunStatus.SUCCEEDED:
            raise RuntimeRepositoryError("injected unavailable store")
        return save(run_id, after_step, state)

    monkeypatch.setattr(repository, "save_checkpoint", fail_success)
    with pytest.raises(RuntimeRepositoryError):
        loop(repository, [ModelResponse.final("done")]).run("hello", run_context=CONTEXT)
    # Model 结果已经独立原子提交；失败的是 FINALIZE，不篡改已成功的 Step。
    assert repository.list_steps("run")[0].status is StepStatus.SUCCEEDED
    assert repository.get_run("run").failure_code == "PERSISTENCE_ERROR"
    assert repository.get_latest_checkpoint("run").state.status is RunStatus.FAILED


def test_sensitive_prompt_is_redacted_only_in_persistence(repository):
    prompt = "Authorization: Bearer private-user-value; token=private-token-value"
    result = loop(repository, [ModelResponse.final("safe")]).run(prompt, run_context=CONTEXT)
    assert result.messages[0].content == prompt
    checkpoint = repository.get_latest_checkpoint("run")
    assert checkpoint.state.redacted
    assert "private-user-value" not in checkpoint.state_json
    assert "private-token-value" not in json.dumps(repository.list_steps("run")[0].input)
