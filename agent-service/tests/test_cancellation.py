import pytest

from devpilot_agent_service.runtime.cancellation import (
    ActiveRunRegistry,
    CancelStatus,
    DuplicateActiveRunError,
)
from devpilot_agent_service.runtime.errors import RunCancelled


def test_registry_accepts_duplicate_cancel_and_remembers_terminal() -> None:
    registry = ActiveRunRegistry(terminal_capacity=2)
    token = registry.register("run-1", "request-1")

    assert registry.cancel("run-1", "request-1") is CancelStatus.ACCEPTED
    assert registry.cancel("run-1", "request-1") is CancelStatus.ACCEPTED
    with pytest.raises(RunCancelled):
        token.raise_if_cancelled()

    registry.complete("run-1")
    assert registry.cancel("run-1", "request-1") is CancelStatus.ALREADY_TERMINAL


def test_registry_rejects_duplicate_active_run_and_wrong_request() -> None:
    registry = ActiveRunRegistry()
    registry.register("run-1", "request-1")

    with pytest.raises(DuplicateActiveRunError):
        registry.register("run-1", "request-2")
    assert registry.cancel("run-1", "wrong") is CancelStatus.NOT_FOUND
