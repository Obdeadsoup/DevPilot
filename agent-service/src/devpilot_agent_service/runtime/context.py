"""一次 Agent Run 的最小跨组件关联上下文。"""

from dataclasses import dataclass

from devpilot_agent_service.memory.store import MemoryScope


@dataclass(frozen=True, slots=True)
class RunContext:
    """Java-authoritative correlation and optional memory scope; never model policy."""

    run_id: str
    request_id: str
    memory_scope: MemoryScope | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.run_id, str) or not self.run_id.strip():
            raise ValueError("run_id must not be blank")
        if not isinstance(self.request_id, str) or not self.request_id.strip():
            raise ValueError("request_id must not be blank")
