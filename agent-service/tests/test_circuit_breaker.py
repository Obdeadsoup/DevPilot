import pytest

from devpilot_agent_service.rpc.circuit_breaker import (
    CircuitOpenError,
    CircuitState,
    ConsecutiveFailureCircuitBreaker,
)


def test_circuit_opens_rejects_and_recovers_through_half_open() -> None:
    now = [10.0]
    circuit = ConsecutiveFailureCircuitBreaker(2, 5.0, clock=lambda: now[0])

    circuit.before_call()
    circuit.record_failure()
    circuit.before_call()
    circuit.record_failure()
    assert circuit.state is CircuitState.OPEN
    with pytest.raises(CircuitOpenError):
        circuit.before_call()

    now[0] = 15.0
    circuit.before_call()
    assert circuit.state is CircuitState.HALF_OPEN
    with pytest.raises(CircuitOpenError):
        circuit.before_call()
    circuit.record_success()
    assert circuit.state is CircuitState.CLOSED


def test_half_open_failure_reopens() -> None:
    now = [0.0]
    circuit = ConsecutiveFailureCircuitBreaker(1, 1.0, clock=lambda: now[0])
    circuit.before_call()
    circuit.record_failure()
    now[0] = 1.0
    circuit.before_call()
    circuit.record_failure()
    assert circuit.state is CircuitState.OPEN
