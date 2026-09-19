"""Process-owned official LangGraph SQLite checkpointer; not a second workflow state machine."""

import sqlite3
from pathlib import Path

from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.checkpoint.sqlite import SqliteSaver


class GraphCheckpointStore:
    def __init__(self, path: str | Path) -> None:
        target = Path(path).expanduser().resolve()
        if str(path) == ":memory:" or not str(path).strip():
            raise ValueError("graph checkpoint requires a file path")
        target.parent.mkdir(parents=True, exist_ok=True)
        self.path = target
        self._connection = sqlite3.connect(target, check_same_thread=False, timeout=10)
        self._connection.execute("PRAGMA journal_mode=WAL")
        # Restrict msgpack deserialization to built-in safe types. LangChain messages
        # serialize through the official JSON constructor protocol.
        self.saver = SqliteSaver(
            self._connection,
            serde=JsonPlusSerializer(pickle_fallback=False, allowed_msgpack_modules=None),
        )
        self.saver.setup()

    def close(self) -> None:
        self._connection.close()
