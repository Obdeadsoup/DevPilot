import json
import socket
import sqlite3

import pytest
from fakes.fake_model import FakeModel
from test_cancel_resume import CONTEXT, Crash, RecordingTool, calls, make_loop

from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.rpc.server import RpcServerConfig, create_server
from devpilot_agent_service.runtime.errors import ResumeRejected
from devpilot_agent_service.runtime.persistence import RunStatus, StepStatus
from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository


def test_bootstrap_reconciles_interrupted_and_cancel_requested_before_serving(repository, tmp_path):
    for run_id in ("running", "cancel", "done"):
        repository.create_run(run_id, "request")
        repository.update_run_status(run_id, RunStatus.RUNNING)
    repository.request_cancel("cancel", "request")
    repository.update_run_status("done", RunStatus.SUCCEEDED)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    server = create_server(
        RpcServerConfig(
            host="127.0.0.1",
            port=port,
            model_mode="fake",
            runtime_db_path=str(tmp_path / "runtime.sqlite3"),
        )
    )
    try:
        run = repository.get_run("running")
        assert run.status is RunStatus.FAILED
        assert run.failure_code == "RUNTIME_INTERRUPTED" and run.retryable
        assert repository.get_run("cancel").status is RunStatus.CANCELLED
        assert not repository.get_run("cancel").retryable
        assert repository.get_run("done").status is RunStatus.SUCCEEDED
        assert repository.reconcile_interrupted_runs() == ()
    finally:
        server.stop(0).wait()


def test_crash_after_model_result_resumes_finalization_without_model_call(repository, monkeypatch):
    update = repository.update_run_status

    def crash_before_terminal(run_id, status, **kwargs):
        if status is RunStatus.SUCCEEDED:
            raise Crash()
        return update(run_id, status, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(repository, "update_run_status", crash_before_terminal)
        with pytest.raises(Crash):
            make_loop(repository, FakeModel([ModelResponse.final("saved answer")])).run(
                "hello",
                run_context=CONTEXT,
            )
    assert repository.get_latest_checkpoint("run").state.next_action == "FINALIZE"
    repository.reconcile_interrupted_runs()
    model = FakeModel([])
    result = make_loop(repository, model).resume(CONTEXT)
    assert model.calls == []
    assert result.final_answer == "saved answer"
    assert repository.get_run("run").current_step == 1


@pytest.mark.parametrize("during_tool", [False, True])
def test_crash_in_completion_transaction_rolls_back_step_and_checkpoint(
    repository,
    monkeypatch,
    during_tool,
):
    tool = RecordingTool()
    save = repository.save_checkpoint

    def crash_after_finish(run_id, after_step, state):
        if (during_tool and state.completed_tool_call_ids) or (
            not during_tool and state.next_action == "TOOLS"
        ):
            raise Crash()
        return save(run_id, after_step, state)

    with monkeypatch.context() as patch:
        patch.setattr(repository, "save_checkpoint", crash_after_finish)
        with pytest.raises(Crash):
            make_loop(repository, FakeModel([calls("one")]), tool).run("hello", run_context=CONTEXT)
    assert repository.list_steps("run")[-1].status is StepStatus.RUNNING
    state = repository.get_latest_checkpoint("run").state
    assert state.completed_tool_call_ids == ()
    assert state.next_action == ("TOOLS" if during_tool else "MODEL")
    repository.reconcile_interrupted_runs()
    model = FakeModel([ModelResponse.final("done")])
    make_loop(repository, model, tool).resume(CONTEXT)
    # 只读 Tool 已返回但 Python 事务未提交：显式恢复会再次调用同一 ID。
    # 此反例说明 SQLite 事务不能替未来 Java 写 Tool 提供副作用幂等。
    assert tool.calls == (["one", "one"] if during_tool else [])


def test_v1_database_migration_preserves_rows_foreign_keys_and_audit_checkpoint(tmp_path):
    path = tmp_path / "legacy.sqlite3"
    old_state = {
        "version": 1,
        "messages": [],
        "current_step": 1,
        "tool_call_count": 0,
        "completed_tool_call_ids": [],
        "status": "RUNNING",
        "next_action": "MODEL",
        "max_steps": 8,
        "max_tool_calls": 16,
        "request_id": "request",
        "redacted": False,
    }
    with sqlite3.connect(path) as connection:
        connection.executescript("""
            PRAGMA foreign_keys=ON;
            CREATE TABLE runtime_runs (
              run_id TEXT PRIMARY KEY, status TEXT NOT NULL CHECK(status IN
              ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
              current_step INTEGER NOT NULL, tool_call_count INTEGER NOT NULL,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL, started_at TEXT,
              finished_at TEXT, failure_code TEXT, failure_message TEXT);
            CREATE TABLE runtime_steps (
              step_id TEXT PRIMARY KEY, run_id TEXT REFERENCES runtime_runs(run_id),
              step_no INTEGER, step_type TEXT, status TEXT, started_at TEXT, finished_at TEXT,
              input TEXT, output TEXT, error TEXT, UNIQUE(run_id,step_no));
            CREATE TABLE runtime_checkpoints (
              checkpoint_id TEXT PRIMARY KEY, run_id TEXT REFERENCES runtime_runs(run_id),
              checkpoint_no INTEGER, after_step INTEGER, state_version INTEGER,
              state_json TEXT, created_at TEXT, UNIQUE(run_id,checkpoint_no));
            INSERT INTO runtime_runs VALUES ('run','RUNNING',1,0,'now','now','now',NULL,NULL,NULL);
            INSERT INTO runtime_steps VALUES
              ('step','run',1,'MODEL_CALL','RUNNING','now',NULL,'{}',NULL,NULL);
        """)
        connection.execute(
            "INSERT INTO runtime_checkpoints VALUES ('cp','run',1,1,1,?,'now')",
            (json.dumps(old_state),),
        )
    repository = SQLiteAgentRuntimeRepository(path)
    assert repository.get_run("run").request_id == "request"
    assert repository.get_latest_checkpoint("run").state_version == 1
    assert json.loads(repository.get_latest_checkpoint("run").state_json)["current_step"] == 1
    with pytest.raises(ValueError, match="version"):
        _ = repository.get_latest_checkpoint("run").state
    assert len(repository.list_steps("run")) == 1
    with sqlite3.connect(path) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 3
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        assert (
            connection.execute("PRAGMA foreign_key_list(runtime_steps)").fetchone()[2]
            == "runtime_runs"
        )
    assert repository.reconcile_interrupted_runs() == ("run",)
    with pytest.raises(ResumeRejected, match="UNSUPPORTED_STATE_VERSION"):
        make_loop(repository, FakeModel([])).resume(CONTEXT)
