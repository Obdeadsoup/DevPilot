"""SQLite USER_PROJECT memory; never populated from retrieved/tool content."""

import hashlib
import json
import re
import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

from devpilot_agent_service.runtime.redaction import REDACTED, RuntimeRedactor

_EXPLICIT = re.compile(
    r"(?:以后|今后|请记住|记住我|from now on|please always|remember that)",
    re.IGNORECASE,
)
_TRANSIENT = re.compile(r"(?:当前|今天|现在|today|right now|这次)")
_SECRET = re.compile(r"(?:密钥|密码|token|secret|api.?key|credential)", re.IGNORECASE)


def _terms(value: str) -> set[str]:
    words = set(re.findall(r"[a-z0-9_]{2,}", value.lower()))
    for sequence in re.findall(r"[\u4e00-\u9fff]+", value):
        words.update(sequence[index:index + 2] for index in range(len(sequence) - 1))
    return words


@dataclass(frozen=True, slots=True)
class MemoryScope:
    workspace_id: int
    project_id: int
    actor_user_id: int

    def __post_init__(self) -> None:
        if any(
            type(value) is not int or value <= 0
            for value in (self.workspace_id, self.project_id, self.actor_user_id)
        ):
            raise ValueError("memory scope IDs must be positive integers")

    @classmethod
    def from_proto(cls, value) -> "MemoryScope":
        return cls(value.workspace_id, value.project_id, value.actor_user_id)


class MemoryStore:
    def __init__(self, path: str | Path, redactor: RuntimeRedactor | None = None) -> None:
        target = Path(path).expanduser().resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(target, check_same_thread=False, timeout=10)
        self._connection.row_factory = sqlite3.Row
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._lock = threading.RLock()
        self._redactor = redactor or RuntimeRedactor()
        with self._lock, self._connection:
            self._connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS run_memory_scope (
                    run_id TEXT PRIMARY KEY, request_id TEXT NOT NULL,
                    workspace_id INTEGER NOT NULL, project_id INTEGER NOT NULL,
                    actor_user_id INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS agent_memory (
                    memory_id TEXT PRIMARY KEY,
                    workspace_id INTEGER NOT NULL, project_id INTEGER NOT NULL,
                    actor_user_id INTEGER NOT NULL, memory_key TEXT NOT NULL,
                    kind TEXT NOT NULL, content TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_accessed_at TEXT,
                    source_run_id TEXT NOT NULL, confidence REAL NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(workspace_id,project_id,actor_user_id,memory_key)
                );
                CREATE TABLE IF NOT EXISTS run_safe_trace (
                    run_id TEXT PRIMARY KEY, trace_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """
            )

    def bind_run(self, run_id: str, request_id: str, scope: MemoryScope) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "INSERT INTO run_memory_scope VALUES (?,?,?,?,?)",
                (run_id, request_id, scope.workspace_id, scope.project_id, scope.actor_user_id),
            )

    def scope_for_run(self, run_id: str, request_id: str) -> MemoryScope | None:
        with self._lock:
            row = self._connection.execute(
                "SELECT workspace_id,project_id,actor_user_id FROM run_memory_scope "
                "WHERE run_id=? AND request_id=?",
                (run_id, request_id),
            ).fetchone()
        return MemoryScope(*row) if row else None

    def upsert(
        self,
        scope: MemoryScope,
        *,
        key: str,
        kind: str,
        content: str,
        source_run_id: str,
        confidence: float,
    ) -> bool:
        if kind not in {"USER_PREFERENCE", "EXPLICIT_PROJECT_CONVENTION", "WORKFLOW_PREFERENCE"}:
            raise ValueError("unsupported memory kind")
        safe = str(self._redactor.redact(content)).strip()[:500]
        if not safe or REDACTED in safe or _SECRET.search(content) or confidence < 0.8:
            return False
        with self._lock, self._connection:
            old = self._connection.execute(
                "SELECT content FROM agent_memory WHERE workspace_id=? AND project_id=? "
                "AND actor_user_id=? AND memory_key=?",
                (
                    scope.workspace_id, scope.project_id, scope.actor_user_id, key,
                ),
            ).fetchone()
            if old is not None and old["content"] == safe:
                return False
            self._connection.execute(
                """
                INSERT INTO agent_memory
                (memory_id,workspace_id,project_id,actor_user_id,memory_key,kind,content,
                 source_run_id,confidence)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(workspace_id,project_id,actor_user_id,memory_key)
                DO UPDATE SET content=excluded.content,kind=excluded.kind,
                updated_at=CURRENT_TIMESTAMP,source_run_id=excluded.source_run_id,
                confidence=excluded.confidence,version=agent_memory.version+1
                """,
                (
                    uuid4().hex, scope.workspace_id, scope.project_id, scope.actor_user_id,
                    key, kind, safe, source_run_id, confidence,
                ),
            )
            return True

    def recall(self, scope: MemoryScope, query: str, *, max_chars: int = 1200) -> str:
        return self.recall_with_ids(scope, query, max_chars=max_chars)[0]

    def recall_with_ids(
        self, scope: MemoryScope, query: str, *, max_chars: int = 1200
    ) -> tuple[str, tuple[str, ...]]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT memory_id,memory_key,content FROM agent_memory WHERE "
                "workspace_id=? AND project_id=? AND actor_user_id=? "
                "ORDER BY updated_at DESC LIMIT 30",
                (scope.workspace_id, scope.project_id, scope.actor_user_id),
            ).fetchall()
        query_terms = _terms(query)
        scored = sorted(
            rows,
            key=lambda row: (
                len(query_terms.intersection(_terms(row["content"]))),
                row["memory_id"],
            ),
            reverse=True,
        )
        selected = []
        accessed = []
        used = 0
        for row in scored:
            score = len(query_terms.intersection(_terms(row["content"])))
            if (
                score == 0
                and row["memory_key"] != "response_language"
                and not re.search(r"偏好|记住|preference|remember", query, re.I)
            ):
                continue
            line = "- " + row["content"]
            if used + len(line) > max_chars:
                continue
            selected.append(line)
            accessed.append(row["memory_id"])
            used += len(line)
        if accessed:
            with self._lock, self._connection:
                self._connection.executemany(
                    "UPDATE agent_memory SET last_accessed_at=CURRENT_TIMESTAMP "
                    "WHERE memory_id=?",
                    [(memory_id,) for memory_id in accessed],
                )
        return "\n".join(selected), tuple(accessed)

    def extract_explicit(self, user_input: str) -> dict | None:
        """Conservative structured extractor over the user message only."""
        text = user_input.strip()
        if (
            not _EXPLICIT.search(text)
            or _TRANSIENT.search(text)
            or _SECRET.search(text)
            or len(text) > 500
        ):
            return None
        language = "response_language" if re.search(r"中文|英文|English", text, re.I) else None
        convention = "java_tool_gateway_priority" if "Java Tool Gateway" in text else None
        return {
            "kind": "USER_PREFERENCE",
            "key": language or convention or "preference:" + hashlib.sha256(
                text.lower().encode("utf-8")
            ).hexdigest()[:16],
            "content": text,
            "confidence": 0.9,
            "reason_code": "EXPLICIT_USER_DIRECTIVE",
        }

    def list_memories(self, scope: MemoryScope) -> tuple[dict, ...]:
        with self._lock:
            rows = self._connection.execute(
                "SELECT memory_key,kind,content,confidence,version FROM agent_memory "
                "WHERE workspace_id=? AND project_id=? AND actor_user_id=? "
                "ORDER BY updated_at DESC",
                (scope.workspace_id, scope.project_id, scope.actor_user_id),
            ).fetchall()
        return tuple(dict(row) for row in rows)

    def delete(self, scope: MemoryScope, key: str) -> bool:
        with self._lock, self._connection:
            result = self._connection.execute(
                "DELETE FROM agent_memory WHERE workspace_id=? AND project_id=? "
                "AND actor_user_id=? AND memory_key=?",
                (scope.workspace_id, scope.project_id, scope.actor_user_id, key),
            )
            return result.rowcount == 1

    def save_trace(self, run_id: str, trace: dict) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                "INSERT OR REPLACE INTO run_safe_trace(run_id,trace_json) VALUES (?,?)",
                (run_id, json.dumps(trace, ensure_ascii=False, sort_keys=True, allow_nan=False)),
            )

    def get_trace(self, run_id: str) -> dict | None:
        with self._lock:
            row = self._connection.execute(
                "SELECT trace_json FROM run_safe_trace WHERE run_id=?", (run_id,)
            ).fetchone()
        return json.loads(row[0]) if row else None

    def close(self) -> None:
        self._connection.close()
