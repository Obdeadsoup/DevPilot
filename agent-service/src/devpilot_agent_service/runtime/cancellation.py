"""进程内活跃 Run 注册表与协作式取消信号。"""

import threading
from collections import deque
from dataclasses import dataclass
from enum import StrEnum

from devpilot_agent_service.runtime.errors import RunCancelled


class CancelStatus(StrEnum):
    ACCEPTED = "ACCEPTED"
    NOT_FOUND = "NOT_FOUND"
    ALREADY_TERMINAL = "ALREADY_TERMINAL"


class DuplicateActiveRunError(RuntimeError):
    """同一 run_id 尚在执行，不能启动第二个 worker。"""


class CancellationToken:
    """线程安全的一次性取消标志；不尝试强杀正在执行的 Provider/Tool 调用。"""

    def __init__(self) -> None:
        self._event = threading.Event()

    def cancel(self) -> None:
        self._event.set()

    def raise_if_cancelled(self) -> None:
        if self._event.is_set():
            raise RunCancelled()


@dataclass(frozen=True, slots=True)
class ActiveRun:
    request_id: str
    token: CancellationToken


class ActiveRunRegistry:
    """共享进程级注册表；有界 terminal tombstone 支撑最近重复取消的稳定应答。"""

    def __init__(self, terminal_capacity: int = 1024) -> None:
        if terminal_capacity < 1:
            raise ValueError("terminal_capacity must be positive")
        self._lock = threading.Lock()
        self._active: dict[str, ActiveRun] = {}
        self._terminal_order: deque[str] = deque()
        self._terminal: set[str] = set()
        self._terminal_capacity = terminal_capacity

    def register(self, run_id: str, request_id: str) -> CancellationToken:
        with self._lock:
            if run_id in self._active:
                raise DuplicateActiveRunError(run_id)
            token = CancellationToken()
            self._active[run_id] = ActiveRun(request_id, token)
            self._terminal.discard(run_id)
            return token

    def complete(self, run_id: str) -> None:
        with self._lock:
            if self._active.pop(run_id, None) is None:
                return
            if run_id not in self._terminal:
                self._terminal.add(run_id)
                self._terminal_order.append(run_id)
            while len(self._terminal_order) > self._terminal_capacity:
                self._terminal.discard(self._terminal_order.popleft())

    def cancel(self, run_id: str, request_id: str) -> CancelStatus:
        with self._lock:
            active = self._active.get(run_id)
            if active is not None and active.request_id == request_id:
                active.token.cancel()
                return CancelStatus.ACCEPTED
            if run_id in self._terminal:
                return CancelStatus.ALREADY_TERMINAL
            return CancelStatus.NOT_FOUND
