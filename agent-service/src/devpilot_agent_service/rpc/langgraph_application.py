"""Read-oriented Runtime ingress adapter, not a durable checkpoint implementation."""

import json
import logging
import threading
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass

from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import ResumeRejected
from devpilot_agent_service.runtime.repository import RunAlreadyExists

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class PreparedWorkflowRun:
    user_input: str
    run_context: RunContext


@dataclass(slots=True)
class _RunRecord:
    prepared: PreparedWorkflowRun | None
    status: str = "PREPARED"
    trace: dict | None = None


class LangGraphRuntimeApplication:
    """Reuse StartRun/StreamRun lifecycle envelopes, explicitly reject parity operations.

    Java owns durable business Run state. Local records are bounded process-only tombstones;
    no Legacy sqlite reconciliation or resume is performed in this mode.
    """

    def __init__(
        self,
        workflow: WorkflowRuntime,
        close_callback: Callable[[], None] | None = None,
        *,
        record_capacity: int = 1024,
    ) -> None:
        if type(record_capacity) is not int or record_capacity < 1:
            raise ValueError("record capacity must be a positive integer")
        self._workflow = workflow
        self._close_callback = close_callback
        self._capacity = record_capacity
        self._lock = threading.Lock()
        self._runs: OrderedDict[str, _RunRecord] = OrderedDict()

    def prepare_run(self, user_input: str, run_context: RunContext) -> PreparedWorkflowRun:
        if not isinstance(user_input, str) or not user_input.strip():
            raise ValueError("workflow query must not be blank")
        if not isinstance(run_context, RunContext):
            raise TypeError("production workflow requires RunContext")
        prepared = PreparedWorkflowRun(user_input, run_context)
        with self._lock:
            if run_context.run_id in self._runs:
                raise RunAlreadyExists(run_context.run_id)
            if len(self._runs) >= self._capacity:
                terminal = next(
                    (
                        key
                        for key, record in self._runs.items()
                        if record.status in {"SUCCEEDED", "FAILED"}
                    ),
                    None,
                )
                if terminal is None:
                    raise ResumeRejected("LANGGRAPH_RUN_CAPACITY")
                del self._runs[terminal]
            self._runs[run_context.run_id] = _RunRecord(prepared)
        return prepared

    def start_run(self, user_input, *, run_context=None, on_event=None, cancellation_token=None):
        return self.execute_prepared(
            self.prepare_run(user_input, run_context),
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    def execute_prepared(self, prepared, *, on_event=None, cancellation_token=None):
        del cancellation_token  # CancelRun is explicitly unsupported, never silently accepted.
        context = prepared.run_context
        with self._lock:
            record = self._runs.get(context.run_id)
            if record is None or record.prepared is not prepared or record.status != "PREPARED":
                raise RunAlreadyExists(context.run_id)
            record.status = "RUNNING"
        try:
            result = self._workflow.invoke(
                prepared.user_input,
                run_context=context,
                on_event=on_event,
            )
        except Exception:
            with self._lock:
                record.status = "FAILED"
                record.prepared = None  # Do not retain raw query after execution.
            LOGGER.info("LangGraph workflow finalStatus=FAILED")
            raise
        with self._lock:
            record.status = "SUCCEEDED"
            record.trace = result.safe_trace()
            record.prepared = None
        LOGGER.info(
            "LangGraph workflow finalStatus=SUCCEEDED trace=%s",
            json.dumps(record.trace, sort_keys=True),
        )
        return result

    def trace_for(self, run_id: str) -> dict | None:
        with self._lock:
            record = self._runs.get(run_id)
            return dict(record.trace) if record and record.trace else None

    def prepare_resume(self, run_context):
        raise ResumeRejected("LANGGRAPH_RESUME_REQUIRES_LEGACY")

    def prepare_approval_resume(self, run_context, proposal_id):
        raise ResumeRejected("LANGGRAPH_APPROVAL_REQUIRES_LEGACY")

    def request_cancel(self, run_id, request_id):
        raise ResumeRejected("LANGGRAPH_CANCEL_REQUIRES_LEGACY")

    def reconcile_interrupted_runs(self) -> tuple[str, ...]:
        return ()  # Never mutate Legacy durable facts merely by selecting langgraph mode.

    def close(self) -> None:
        if self._close_callback:
            self._close_callback()
