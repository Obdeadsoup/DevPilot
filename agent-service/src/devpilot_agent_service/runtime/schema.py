"""单实例 SQLite schema v3；原地保留历史 Run、Step 和 Checkpoint。"""

import json
import sqlite3

_SCHEMA = """
CREATE TABLE IF NOT EXISTS runtime_runs (
    run_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK(status IN
        ('PENDING','RUNNING','CANCEL_REQUESTED','WAITING_APPROVAL','SUCCEEDED','FAILED','CANCELLED')),
    current_step INTEGER NOT NULL DEFAULT 0 CHECK(current_step >= 0),
    tool_call_count INTEGER NOT NULL DEFAULT 0 CHECK(tool_call_count >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT,
    failure_code TEXT,
    failure_message TEXT,
    request_id TEXT,
    retryable INTEGER NOT NULL DEFAULT 0 CHECK(retryable IN (0,1)),
    version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS runtime_steps (
    step_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runtime_runs(run_id),
    step_no INTEGER NOT NULL CHECK(step_no > 0),
    step_type TEXT NOT NULL CHECK(step_type IN ('MODEL_CALL','TOOL_CALL')),
    status TEXT NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED')),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    input TEXT NOT NULL,
    output TEXT,
    error TEXT,
    UNIQUE(run_id, step_no)
);
CREATE TABLE IF NOT EXISTS runtime_checkpoints (
    checkpoint_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runtime_runs(run_id),
    checkpoint_no INTEGER NOT NULL CHECK(checkpoint_no > 0),
    after_step INTEGER NOT NULL CHECK(after_step >= 0),
    state_version INTEGER NOT NULL CHECK(state_version > 0),
    state_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(run_id, checkpoint_no)
);
CREATE INDEX IF NOT EXISTS idx_runtime_runs_status ON runtime_runs(status);
"""


def initialize_schema(connection: sqlite3.Connection) -> None:
    # 改 CHECK 需要重建父表；禁止用 writable_schema 绕过 SQLite 完整性检查。
    # foreign_keys 只能在事务外切换，整个迁移仍在一个事务内提交或回滚。
    connection.execute("PRAGMA foreign_keys=OFF")
    try:
        connection.execute("BEGIN IMMEDIATE")
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version > 3:
            raise ValueError("unsupported runtime database version")
        columns = {row[1] for row in connection.execute("PRAGMA table_info(runtime_runs)")}
        statements = [sql.strip() for sql in _SCHEMA.split(";") if sql.strip()]
        if columns and version < 3:
            connection.execute(statements[0].replace("runtime_runs", "runtime_runs_v3", 1))
            ordered = (
                "run_id", "status", "current_step", "tool_call_count", "created_at", "updated_at",
                "started_at", "finished_at", "failure_code", "failure_message", "request_id",
                "retryable", "version",
            )
            fields = ",".join(field for field in ordered if field in columns)
            connection.execute(
                f"INSERT INTO runtime_runs_v3 ({fields}) SELECT {fields} FROM runtime_runs"
            )
            connection.execute("DROP TABLE runtime_runs")
            connection.execute("ALTER TABLE runtime_runs_v3 RENAME TO runtime_runs")
            # 仅提取旧快照中的关联字段，不凭 messages 推测恢复位置。
            for row in connection.execute(
                "SELECT run_id,state_json FROM runtime_checkpoints ORDER BY checkpoint_no"
            ).fetchall():
                try:
                    request_id = json.loads(row[1]).get("request_id")
                except (ValueError, AttributeError):
                    continue
                if isinstance(request_id, str) and request_id.strip():
                    connection.execute(
                        "UPDATE runtime_runs SET request_id=? WHERE run_id=?", (request_id, row[0])
                    )
        for statement in statements:
            connection.execute(statement)
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise ValueError("runtime database foreign key check failed")
        connection.execute("PRAGMA user_version=3")
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.execute("PRAGMA foreign_keys=ON")
