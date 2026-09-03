"""仅保存 runtime_*；短 SQLite 事务不会跨越 Model、Tool 或事件队列等待。"""

import json
import sqlite3
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from devpilot_agent_service.runtime.persistence import (
    CancelDecision,
    RunStatus,
    RuntimeCheckpoint,
    RuntimeCheckpointState,
    RuntimeRun,
    RuntimeStep,
    StepStatus,
    StepType,
)
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.runtime.repository import (
    RunAlreadyExists,
    RuntimeRepositoryError,
    RuntimeStateConflict,
)
from devpilot_agent_service.runtime.schema import initialize_schema


class SQLiteAgentRuntimeRepository:
    def __init__(self, path: str | Path, *, redactor: RuntimeRedactor | None = None) -> None:
        if not str(path).strip() or str(path) == ":memory:":
            raise ValueError("runtime database requires a file path; tests use a temporary file")
        self._path = Path(path).expanduser().resolve()
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._redactor = redactor or RuntimeRedactor()
        try:
            connection = self._connect()
            try:
                connection.execute("PRAGMA journal_mode=WAL")
                initialize_schema(connection)
            finally:
                connection.close()
        except sqlite3.Error:
            raise RuntimeRepositoryError("runtime store initialization failed") from None

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    @contextmanager
    def transaction(self) -> Iterator[None]:
        if getattr(self._local, "connection", None) is not None:
            yield
            return
        connection = None
        try:
            connection = self._connect()
            self._local.connection = connection
            # BEGIN IMMEDIATE 在分配序号前获取写锁；跨线程/Repository 实例也不会 MAX+1 冲突。
            # 嵌套 CRUD 共用当前事务，异常会回滚整个执行边界。
            connection.execute("BEGIN IMMEDIATE")
            yield
            connection.commit()
        except BaseException as error:
            if connection is not None:
                connection.rollback()
            if isinstance(error, sqlite3.Error):
                raise RuntimeRepositoryError("runtime store operation failed") from None
            raise
        finally:
            self._local.connection = None
            if connection is not None:
                connection.close()

    def _execute(self, sql: str, parameters: tuple = ()) -> sqlite3.Cursor:
        return self._local.connection.execute(sql, parameters)

    def _json(self, value: object) -> str:
        return json.dumps(
            self._redactor.redact(value), ensure_ascii=False, sort_keys=True, allow_nan=False
        )

    def create_run(self, run_id: str, request_id: str | None = None) -> RuntimeRun:
        if not isinstance(run_id, str) or not run_id.strip():
            raise ValueError("run_id must not be blank")
        with self.transaction():
            if self.get_run(run_id) is not None:
                raise RunAlreadyExists("runtime run already exists")
            now = _now()
            self._execute(
                "INSERT INTO runtime_runs(run_id,status,created_at,updated_at,request_id) "
                "VALUES (?,?,?,?,?)",
                (run_id, RunStatus.PENDING, now, now, request_id),
            )
            return self.get_run(run_id)

    def get_run(self, run_id: str) -> RuntimeRun | None:
        with self.transaction():
            row = self._execute("SELECT * FROM runtime_runs WHERE run_id=?", (run_id,)).fetchone()
            if row is None:
                return None
            data = dict(row)
            data["status"] = RunStatus(data["status"])
            data["retryable"] = bool(data["retryable"])
            return RuntimeRun(**data)

    def compare_and_set_status(
        self,
        run_id: str,
        expected_statuses: tuple[RunStatus, ...],
        status: RunStatus,
        *,
        current_step: int | None = None,
        tool_call_count: int | None = None,
        failure_code: str | None = None,
        failure_message: str | None = None,
        retryable: bool = False,
        expected_version: int | None = None,
    ) -> bool:
        with self.transaction():
            run = self.get_run(run_id)
            if run is None or run.status not in expected_statuses:
                return False
            if expected_version is not None and run.version != expected_version:
                return False
            allowed = {
                RunStatus.PENDING: {RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.FAILED},
                RunStatus.RUNNING: {
                    RunStatus.RUNNING,
                    RunStatus.SUCCEEDED,
                    RunStatus.FAILED,
                    RunStatus.CANCEL_REQUESTED,
                },
                RunStatus.CANCEL_REQUESTED: {RunStatus.CANCEL_REQUESTED, RunStatus.CANCELLED},
                RunStatus.FAILED: {RunStatus.RUNNING} if run.retryable else set(),
            }
            if status not in allowed.get(run.status, set()):
                raise RuntimeStateConflict("invalid runtime run transition")
            current_step = run.current_step if current_step is None else current_step
            tool_call_count = run.tool_call_count if tool_call_count is None else tool_call_count
            if current_step < run.current_step or tool_call_count < run.tool_call_count:
                raise RuntimeStateConflict("runtime progress cannot move backwards")
            if status is RunStatus.FAILED and not failure_code:
                raise RuntimeStateConflict("failed run requires a failure code")
            now = _now()
            terminal = status in {RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED}
            # status + version CAS：Cancel 与 Final 只能有一个获胜，终态不可被迟到写覆盖。
            cursor = self._execute(
                """UPDATE runtime_runs SET status=?,current_step=?,tool_call_count=?,updated_at=?,
                started_at=COALESCE(started_at,?),finished_at=?,failure_code=?,failure_message=?,
                retryable=?,version=version+1 WHERE run_id=? AND status=? AND version=?""",
                (
                    status,
                    current_step,
                    tool_call_count,
                    now,
                    now if status is RunStatus.RUNNING else None,
                    now if terminal else None,
                    self._redactor.redact(failure_code) if status is RunStatus.FAILED else None,
                    self._redactor.redact(failure_message) if status is RunStatus.FAILED else None,
                    int(retryable and status is RunStatus.FAILED),
                    run_id,
                    run.status,
                    run.version,
                ),
            )
            return cursor.rowcount == 1

    def update_run_status(
        self,
        run_id: str,
        status: RunStatus,
        *,
        current_step: int | None = None,
        tool_call_count: int | None = None,
        failure_code: str | None = None,
        failure_message: str | None = None,
        retryable: bool = False,
    ) -> RuntimeRun:
        with self.transaction():
            run = self.get_run(run_id)
            if run is None or not self.compare_and_set_status(
                run_id,
                (run.status,),
                status,
                current_step=current_step,
                tool_call_count=tool_call_count,
                failure_code=failure_code,
                failure_message=failure_message,
                retryable=retryable,
                expected_version=run.version,
            ):
                raise RuntimeStateConflict("runtime run transition lost")
            return self.get_run(run_id)

    def request_cancel(self, run_id: str, request_id: str | None) -> CancelDecision | None:
        with self.transaction():
            run = self.get_run(run_id)
            if run is None or run.request_id != request_id:
                return None
            if run.status in {RunStatus.PENDING, RunStatus.RUNNING}:
                status = (
                    RunStatus.CANCELLED
                    if run.status is RunStatus.PENDING
                    else RunStatus.CANCEL_REQUESTED
                )
                self.update_run_status(run_id, status)
            return CancelDecision(
                self.get_run(run_id),
                run.status
                in {
                    RunStatus.PENDING,
                    RunStatus.RUNNING,
                    RunStatus.CANCEL_REQUESTED,
                },
            )

    def reconcile_interrupted_runs(self) -> tuple[str, ...]:
        # 只在单实例服务开始接收请求前运行；这不是 lease/heartbeat，不能并行接管其他实例。
        with self.transaction():
            rows = self._execute(
                "SELECT run_id,status FROM runtime_runs WHERE status IN "
                "('PENDING','RUNNING','CANCEL_REQUESTED')"
            ).fetchall()
            for row in rows:
                cancelled = row["status"] == RunStatus.CANCEL_REQUESTED
                self.update_run_status(
                    row["run_id"],
                    RunStatus.CANCELLED if cancelled else RunStatus.FAILED,
                    failure_code=None if cancelled else "RUNTIME_INTERRUPTED",
                    failure_message=None if cancelled else "runtime execution interrupted",
                    retryable=not cancelled,
                )
                self._execute(
                    "UPDATE runtime_steps SET status='FAILED',finished_at=?,error=? "
                    "WHERE run_id=? AND status='RUNNING'",
                    (
                        _now(),
                        self._json({"code": "CANCELLED" if cancelled else "RUNTIME_INTERRUPTED"}),
                        row["run_id"],
                    ),
                )
            return tuple(row["run_id"] for row in rows)

    def create_step(self, run_id: str, step_type: StepType, input: object) -> RuntimeStep:
        with self.transaction():
            run = self.get_run(run_id)
            if run is None or run.status is not RunStatus.RUNNING:
                raise RuntimeStateConflict("step requires a running run")
            # Step 序号涵盖每个 Model/Tool 动作，与模型轮次分离。
            step_no = self._execute(
                "SELECT COALESCE(MAX(step_no),0)+1 FROM runtime_steps WHERE run_id=?",
                (run_id,),
            ).fetchone()[0]
            step_id = str(uuid4())
            self._execute(
                """INSERT INTO runtime_steps
                (step_id,run_id,step_no,step_type,status,started_at,input)
                VALUES (?,?,?,?,?,?,?)""",
                (
                    step_id,
                    run_id,
                    step_no,
                    step_type,
                    StepStatus.RUNNING,
                    _now(),
                    self._json(input),
                ),
            )
            return _step(
                self._execute(
                    "SELECT * FROM runtime_steps WHERE step_id=?",
                    (step_id,),
                ).fetchone()
            )

    def finish_step(self, step_id: str, output: object) -> None:
        self._end_step(step_id, StepStatus.SUCCEEDED, output=output)

    def fail_step(self, step_id: str, error: object) -> None:
        self._end_step(step_id, StepStatus.FAILED, error=error)

    def _end_step(
        self,
        step_id: str,
        status: StepStatus,
        *,
        output: object = None,
        error: object = None,
    ) -> None:
        with self.transaction():
            cursor = self._execute(
                """UPDATE runtime_steps SET status=?,finished_at=?,output=?,error=?
                WHERE step_id=? AND status='RUNNING'""",
                (status, _now(), self._json(output), self._json(error), step_id),
            )
            if cursor.rowcount != 1:
                raise RuntimeStateConflict("step is missing or already terminal")

    def list_steps(self, run_id: str) -> tuple[RuntimeStep, ...]:
        with self.transaction():
            return tuple(
                _step(row)
                for row in self._execute(
                    "SELECT * FROM runtime_steps WHERE run_id=? ORDER BY step_no",
                    (run_id,),
                ).fetchall()
            )

    def save_checkpoint(
        self,
        run_id: str,
        after_step: int,
        state: RuntimeCheckpointState,
    ) -> RuntimeCheckpoint:
        with self.transaction():
            run = self.get_run(run_id)
            if run is None or (run.status, run.current_step, run.tool_call_count) != (
                state.status,
                state.current_step,
                state.tool_call_count,
            ):
                raise RuntimeStateConflict("checkpoint must match runtime run progress")
            last = self._execute(
                "SELECT COALESCE(MAX(step_no),0) FROM runtime_steps WHERE run_id=?",
                (run_id,),
            ).fetchone()[0]
            if after_step != last:
                raise RuntimeStateConflict("checkpoint must reference the latest step")
            # Checkpoint 自己编号；失败/取消可以在同一 after_step 后保存新的终态快照。
            number = self._execute(
                "SELECT COALESCE(MAX(checkpoint_no),0)+1 FROM runtime_checkpoints WHERE run_id=?",
                (run_id,),
            ).fetchone()[0]
            raw = state.to_dict()
            safe = self._redactor.redact(raw)
            safe["redacted"] = state.redacted or safe != raw
            state_json = json.dumps(safe, ensure_ascii=False, sort_keys=True, allow_nan=False)
            checkpoint = RuntimeCheckpoint(
                str(uuid4()),
                run_id,
                number,
                after_step,
                state.version,
                state_json,
                _now(),
            )
            self._execute(
                "INSERT INTO runtime_checkpoints VALUES (?,?,?,?,?,?,?)",
                (
                    checkpoint.checkpoint_id,
                    run_id,
                    number,
                    after_step,
                    state.version,
                    state_json,
                    checkpoint.created_at,
                ),
            )
            return checkpoint

    def get_latest_checkpoint(self, run_id: str) -> RuntimeCheckpoint | None:
        with self.transaction():
            row = self._execute(
                """SELECT * FROM runtime_checkpoints WHERE run_id=?
                ORDER BY checkpoint_no DESC LIMIT 1""",
                (run_id,),
            ).fetchone()
            return RuntimeCheckpoint(**dict(row)) if row is not None else None


def _now() -> str:
    return datetime.now(UTC).isoformat()


def _step(row: sqlite3.Row) -> RuntimeStep:
    data = dict(row)
    data["step_type"] = StepType(data["step_type"])
    data["status"] = StepStatus(data["status"])
    for name in ("input", "output", "error"):
        data[name] = json.loads(data[name]) if data[name] is not None else None
    return RuntimeStep(**data)
