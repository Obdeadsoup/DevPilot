"""Python→Java Tool Gateway 的最小进程内 Circuit Breaker。"""

import threading
import time
from collections.abc import Callable
from enum import StrEnum


class CircuitState(StrEnum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class CircuitOpenError(RuntimeError):
    pass


class ConsecutiveFailureCircuitBreaker:
    """连续传输失败触发 OPEN；窗口到期只放行一个 HALF_OPEN probe。"""

    def __init__(
        self,
        failure_threshold: int = 3,
        open_seconds: float = 10.0,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if failure_threshold < 1 or open_seconds <= 0:
            raise ValueError("circuit breaker configuration must be positive")
        self._failure_threshold = failure_threshold
        self._open_seconds = open_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._state = CircuitState.CLOSED
        self._failures = 0
        self._opened_at = 0.0
        self._half_open_in_flight = False

    def before_call(self) -> None:
        with self._lock:
            if self._state is CircuitState.OPEN:
                if self._clock() - self._opened_at < self._open_seconds:
                    raise CircuitOpenError()
                self._state = CircuitState.HALF_OPEN
            if self._state is CircuitState.HALF_OPEN:
                if self._half_open_in_flight:
                    raise CircuitOpenError()
                self._half_open_in_flight = True

    def record_success(self) -> None:
        with self._lock:
            self._state = CircuitState.CLOSED
            self._failures = 0
            self._half_open_in_flight = False

    def record_failure(self) -> None:
        with self._lock:
            self._half_open_in_flight = False
            self._failures += 1
            if self._state is CircuitState.HALF_OPEN or self._failures >= self._failure_threshold:
                self._state = CircuitState.OPEN
                self._opened_at = self._clock()

    @property
    def state(self) -> CircuitState:
        with self._lock:
            return self._state
