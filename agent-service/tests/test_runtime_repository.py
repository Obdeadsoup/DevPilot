import json
import sqlite3
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest

from devpilot_agent_service.config import create_runtime_repository
from devpilot_agent_service.model.types import ToolCall
from devpilot_agent_service.runtime.message import Message
from devpilot_agent_service.runtime.persistence import (
    RunStatus,
    RuntimeCheckpointState,
    StepStatus,
    StepType,
)
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.runtime.repository import RunAlreadyExists, RuntimeStateConflict
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository


def state(**overrides):
    return replace(
        RuntimeCheckpointState(
            messages=(Message.user("hello"),),
            current_step=0,
            tool_call_count=0,
            completed_tool_call_ids=(),
            status=RunStatus.RUNNING,
            next_action="MODEL",
            max_steps=8,
            max_tool_calls=16,
        ),
        **overrides,
    )


def running(repository):
    repository.create_run("run")
    return repository.update_run_status("run", RunStatus.RUNNING)


def test_run_lifecycle_and_terminal_is_immutable(repository):
    pending = repository.create_run("run")
    assert pending.status is RunStatus.PENDING
    assert pending.started_at is pending.finished_at is None
    active = repository.update_run_status("run", RunStatus.RUNNING)
    terminal = repository.update_run_status(
        "run",
        RunStatus.SUCCEEDED,
        current_step=1,
        tool_call_count=0,
    )
    assert terminal.started_at == active.started_at
    assert terminal.created_at <= terminal.started_at <= terminal.finished_at
    assert terminal.finished_at == terminal.updated_at
    with pytest.raises(RuntimeStateConflict):
        repository.update_run_status("run", RunStatus.RUNNING)
    with pytest.raises(RunAlreadyExists):
        repository.create_run("run")
    assert repository.get_run("missing") is None
    assert repository.get_latest_checkpoint("missing") is None


def test_step_order_success_failure_and_checkpoint_round_trip(repository, tmp_path):
    running(repository)
    call = ToolCall("call-1", "echo", {"text": "hello"})
    first = repository.create_step("run", StepType.MODEL_CALL, {"prompt": "hello"})
    repository.finish_step(first.step_id, {"call_id": call.call_id})
    first_cp = repository.save_checkpoint(
        "run",
        1,
        state(
            messages=(Message.user("hello"), Message.assistant_tool_calls([call])),
            next_action="TOOLS",
        ),
    )
    second = repository.create_step("run", StepType.TOOL_CALL, dict(call.arguments))
    repository.fail_step(second.step_id, {"code": "TOOL_ERROR"})
    repository.update_run_status("run", RunStatus.FAILED, failure_code="TOOL_ERROR")
    last_cp = repository.save_checkpoint(
        "run",
        2,
        state(
            status=RunStatus.FAILED,
            next_action="TERMINAL",
        ),
    )
    assert [step.step_no for step in repository.list_steps("run")] == [1, 2]
    assert [step.status for step in repository.list_steps("run")] == [
        StepStatus.SUCCEEDED,
        StepStatus.FAILED,
    ]
    assert repository.list_steps("run")[1].error == {"code": "TOOL_ERROR"}
    assert first_cp.state.messages[-1].tool_calls == (call,)
    assert not first_cp.state.redacted
    assert last_cp.checkpoint_no == 2
    assert repository.get_latest_checkpoint("run") == last_cp
    with pytest.raises(RuntimeStateConflict):
        repository.finish_step(second.step_id, {})

    # 新 Python 进程读取，证明结果来自磁盘而非 Repository 对象缓存。
    probe = subprocess.run(
        [
            sys.executable,
            "-c",
            "import sys; from devpilot_agent_service.runtime.sqlite_repository "
            "import SQLiteAgentRuntimeRepository; "
            "r=SQLiteAgentRuntimeRepository(sys.argv[1]); "
            "print(r.get_run('run').status, r.get_latest_checkpoint('run').checkpoint_no)",
            str(tmp_path / "runtime.sqlite3"),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert probe.stdout.strip() == "FAILED 2"


def test_boundary_rolls_back_step_run_and_checkpoint_together(repository):
    running(repository)
    step = repository.create_step("run", StepType.MODEL_CALL, {})
    with pytest.raises(RuntimeError, match="injected"):
        with repository.transaction():
            repository.finish_step(step.step_id, {"answer": "done"})
            repository.update_run_status("run", RunStatus.SUCCEEDED)
            repository.save_checkpoint(
                "run",
                1,
                state(
                    status=RunStatus.SUCCEEDED,
                    next_action="TERMINAL",
                ),
            )
            raise RuntimeError("injected commit boundary failure")
    assert repository.list_steps("run")[0].status is StepStatus.RUNNING
    assert repository.get_run("run").status is RunStatus.RUNNING
    assert repository.get_latest_checkpoint("run") is None


def test_concurrent_instances_claim_once_and_allocate_monotonic_numbers(tmp_path):
    path = tmp_path / "concurrent.sqlite3"
    stores = [SQLiteAgentRuntimeRepository(path) for _ in range(4)]

    def claim(index):
        try:
            stores[index % 4].create_run("run")
            return True
        except RunAlreadyExists:
            return False

    with ThreadPoolExecutor(max_workers=4) as workers:
        assert sum(workers.map(claim, range(12))) == 1
        stores[0].update_run_status("run", RunStatus.RUNNING)
        steps = list(
            workers.map(
                lambda i: stores[i % 4].create_step("run", StepType.MODEL_CALL, {}),
                range(12),
            )
        )
        for step in steps:
            stores[0].finish_step(step.step_id, {})
        snapshots = list(
            workers.map(
                lambda i: stores[i % 4].save_checkpoint("run", 12, state()),
                range(12),
            )
        )
    assert sorted(step.step_no for step in steps) == list(range(1, 13))
    assert sorted(cp.checkpoint_no for cp in snapshots) == list(range(1, 13))
    assert stores[0].get_latest_checkpoint("run").checkpoint_no == 12


def test_progress_and_checkpoint_reference_must_match(repository):
    running(repository)
    repository.update_run_status("run", RunStatus.RUNNING, current_step=2, tool_call_count=1)
    with pytest.raises(RuntimeStateConflict):
        repository.update_run_status("run", RunStatus.RUNNING, current_step=1)
    with pytest.raises(RuntimeStateConflict):
        repository.save_checkpoint("run", 0, state())
    with pytest.raises(RuntimeStateConflict):
        repository.save_checkpoint("run", 5, state(current_step=2, tool_call_count=1))


def test_unknown_checkpoint_version_is_rejected():
    data = state().to_dict()
    data["version"] = 99
    with pytest.raises(ValueError, match="version"):
        RuntimeCheckpointState.from_json(json.dumps(data))


def test_configured_credentials_are_removed_even_without_labels(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_RUNTIME_DB_PATH", str(tmp_path / "configured.sqlite3"))
    monkeypatch.setenv("DEEPSEEK_API_KEY", "opaque-provider-value")
    monkeypatch.setenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", "opaque-internal-value")
    repository = create_runtime_repository()
    running(repository)
    checkpoint = repository.save_checkpoint(
        "run",
        0,
        state(messages=(Message.user("echo opaque-provider-value and opaque-internal-value"),)),
    )
    assert checkpoint.state.redacted
    assert "opaque-provider-value" not in checkpoint.state_json
    assert "opaque-internal-value" not in checkpoint.state_json


def test_redaction_covers_nested_json_text_and_does_not_mutate_input(tmp_path):
    path = tmp_path / "secrets.sqlite3"
    repository = SQLiteAgentRuntimeRepository(path, redactor=RuntimeRedactor(["opaque-real-key"]))
    running(repository)
    payload = {
        "nested": {"Authorization": "Bearer credential-value", "serviceKey": "internal-value"},
        "content": '{"credentials":{"password":"private-password"},"safe":"visible"}',
        "prompt": "token=abc123; API key: xyz987; opaque-real-key; Bearer bearer-value",
    }
    original = json.dumps(payload)
    step = repository.create_step("run", StepType.MODEL_CALL, payload)
    repository.fail_step(step.step_id, payload)
    repository.update_run_status(
        "run",
        RunStatus.FAILED,
        failure_code="MODEL_ERROR",
        failure_message="opaque-real-key",
    )
    checkpoint = repository.save_checkpoint(
        "run",
        1,
        state(
            messages=(Message.user(json.dumps(payload)),),
            status=RunStatus.FAILED,
            next_action="TERMINAL",
        ),
    )
    assert checkpoint.state.redacted
    assert json.dumps(payload) == original
    with sqlite3.connect(path) as connection:
        dump = "\n".join(connection.iterdump())
    for secret in (
        "credential-value",
        "internal-value",
        "private-password",
        "abc123",
        "xyz987",
        "opaque-real-key",
        "bearer-value",
    ):
        assert secret not in dump
        assert secret.encode() not in path.read_bytes()
    assert "visible" in dump
    assert "[REDACTED]" in dump
