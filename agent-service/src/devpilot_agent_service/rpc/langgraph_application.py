"""Single production LangGraph AgentRun with operational lifecycle facts."""

import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass

from devpilot_agent_service.harness.workflow import WorkflowRuntime
from devpilot_agent_service.memory.store import MemoryStore
from devpilot_agent_service.runtime.cancellation import CancellationToken
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import ApprovalRequired, ResumeRejected, RunCancelled
from devpilot_agent_service.runtime.events import RuntimeEvent, RuntimeEventType
from devpilot_agent_service.runtime.persistence import RunStatus, StepType
from devpilot_agent_service.runtime.recovery import RETRYABLE_CODES, classify_failure
from devpilot_agent_service.runtime.repository import AgentRuntimeRepository, RunAlreadyExists
from devpilot_agent_service.tools.base import ToolProposalResolution
from devpilot_agent_service.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class PreparedWorkflowRun:
    run_context: RunContext
    user_input: str | None = None
    resume: bool = False
    resolution: ToolProposalResolution | None = None


class LangGraphRuntimeApplication:
    """Operational SQLite records status; LangGraph owns continuation and node state."""

    def __init__(
        self,
        workflow: WorkflowRuntime,
        repository: AgentRuntimeRepository,
        registry: ToolRegistry,
        memory: MemoryStore,
        close_callback: Callable[[], None] | None = None,
    ) -> None:
        self._workflow = workflow
        self._repository = repository
        self._registry = registry
        self._memory = memory
        self._close_callback = close_callback

    def prepare_run(self, user_input: str, run_context: RunContext) -> PreparedWorkflowRun:
        if not isinstance(user_input, str) or not user_input.strip():
            raise ValueError("workflow query must not be blank")
        if not isinstance(run_context, RunContext):
            raise TypeError("production workflow requires RunContext")
        self._repository.create_run(run_context.run_id, run_context.request_id)
        if run_context.memory_scope is not None:
            self._memory.bind_run(
                run_context.run_id, run_context.request_id, run_context.memory_scope
            )
        return PreparedWorkflowRun(run_context, user_input)

    def prepare_resume(self, run_context: RunContext) -> PreparedWorkflowRun:
        run = self._repository.get_run(run_context.run_id)
        if run is None:
            raise ResumeRejected("RUN_NOT_FOUND")
        if run.request_id != run_context.request_id:
            raise ResumeRejected("RUN_IDENTITY_MISMATCH")
        if (
            run.status is not RunStatus.FAILED
            or not run.retryable
            or run.failure_code not in RETRYABLE_CODES
        ):
            raise ResumeRejected("RUN_NOT_RETRYABLE")
        snapshot = self._workflow.checkpoint_state(run_context)
        if snapshot is None or not snapshot.values or not snapshot.next:
            raise ResumeRejected("CHECKPOINT_NOT_RESUMABLE")
        if (snapshot.values.get("run_id"), snapshot.values.get("request_id")) != (
            run_context.run_id,
            run_context.request_id,
        ):
            raise ResumeRejected("CHECKPOINT_IDENTITY_MISMATCH")
        if not self._repository.compare_and_set_status(
            run_context.run_id,
            (RunStatus.FAILED,),
            RunStatus.RUNNING,
            expected_version=run.version,
        ):
            raise ResumeRejected("RUN_STATE_CONFLICT")
        return PreparedWorkflowRun(
            RunContext(
                run_context.run_id,
                run_context.request_id,
                self._memory.scope_for_run(run_context.run_id, run_context.request_id),
            ),
            resume=True,
        )

    def prepare_approval_resume(self, run_context: RunContext, proposal_id: str):
        run = self._repository.get_run(run_context.run_id)
        if run is None or run.request_id != run_context.request_id:
            raise ResumeRejected("RUN_NOT_FOUND")
        if run.status is not RunStatus.WAITING_APPROVAL:
            raise ResumeRejected("RUN_NOT_WAITING_APPROVAL")
        snapshot = self._workflow.checkpoint_state(run_context)
        if snapshot is None or "approval_wait" not in snapshot.next:
            raise ResumeRejected("INVALID_APPROVAL_CHECKPOINT")
        pending = snapshot.values.get("pending_proposal")
        if (
            not isinstance(pending, dict)
            or pending.get("proposal_id") != proposal_id
            or pending.get("tool_name") != "task.create"
            or (snapshot.values.get("run_id"), snapshot.values.get("request_id"))
            != (run_context.run_id, run_context.request_id)
        ):
            raise ResumeRejected("PROPOSAL_MISMATCH")
        resolution = self._registry.get_proposal_resolution(
            "task.create", run_context=run_context, proposal_id=proposal_id
        )
        if any(
            getattr(resolution, key) != pending[key]
            for key in ("proposal_id", "tool_call_id", "tool_name")
        ):
            raise ResumeRejected("PROPOSAL_MISMATCH")
        if resolution.status not in {"EXECUTED", "REJECTED", "EXPIRED", "FAILED"}:
            raise ResumeRejected("PROPOSAL_NOT_RESOLVED")
        if not self._repository.compare_and_set_status(
            run_context.run_id,
            (RunStatus.WAITING_APPROVAL,),
            RunStatus.RUNNING,
            expected_version=run.version,
        ):
            raise ResumeRejected("RUN_STATE_CONFLICT")
        return PreparedWorkflowRun(
            RunContext(
                run_context.run_id,
                run_context.request_id,
                self._memory.scope_for_run(run_context.run_id, run_context.request_id),
            ),
            resume=True,
            resolution=resolution,
        )

    def start_run(
        self,
        user_input: str,
        *,
        run_context: RunContext,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ):
        return self.execute_prepared(
            self.prepare_run(user_input, run_context),
            on_event=on_event,
            cancellation_token=cancellation_token,
        )

    def execute_prepared(
        self,
        prepared: PreparedWorkflowRun,
        *,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        cancellation_token: CancellationToken | None = None,
    ):
        run_id = prepared.run_context.run_id
        started = time.monotonic()
        active_model_step = None
        active_tool_steps = {}
        if not prepared.resume and not self._repository.compare_and_set_status(
            run_id, (RunStatus.PENDING,), RunStatus.RUNNING
        ):
            self._cancel_if_requested(run_id)
            raise RunAlreadyExists(run_id)

        def safe_point() -> None:
            self._cancel_if_requested(run_id)
            if cancellation_token is not None:
                cancellation_token.raise_if_cancelled()

        def emit(event: RuntimeEvent) -> None:
            nonlocal active_model_step
            safe_point()
            if event.type is RuntimeEventType.MODEL_STEP_STARTED:
                if active_model_step is not None:
                    self._repository.finish_step(active_model_step, {"status": "COMPLETED"})
                active_model_step = self._repository.create_step(
                    run_id, StepType.MODEL_CALL, {"step": event.step}
                ).step_id
            elif event.type is RuntimeEventType.TOOL_STARTED:
                if active_model_step is not None:
                    self._repository.finish_step(active_model_step, {"status": "COMPLETED"})
                    active_model_step = None
                active_tool_steps[event.tool_name] = self._repository.create_step(
                    run_id, StepType.TOOL_CALL,
                    {"step": event.step, "name": event.tool_name},
                ).step_id
            elif event.type is RuntimeEventType.TOOL_COMPLETED:
                step_id = active_tool_steps.pop(event.tool_name, None)
                if step_id is not None:
                    self._repository.finish_step(step_id, {"status": "COMPLETED"})
            run = self._repository.get_run(run_id)
            if run is not None and run.status is RunStatus.RUNNING:
                step = max(run.current_step, event.step)
                tools = run.tool_call_count + (
                    1 if event.type is RuntimeEventType.TOOL_COMPLETED else 0
                )
                self._repository.compare_and_set_status(
                    run_id,
                    (RunStatus.RUNNING,),
                    RunStatus.RUNNING,
                    current_step=step,
                    tool_call_count=tools,
                    expected_version=run.version,
                )
            if on_event:
                on_event(event)

        try:
            safe_point()
            result = (
                self._workflow.resume_approval(
                    prepared.run_context,
                    prepared.resolution,
                    on_event=emit,
                    safe_point=safe_point,
                )
                if prepared.resolution is not None
                else self._workflow.resume(
                    prepared.run_context, on_event=emit, safe_point=safe_point
                )
                if prepared.resume
                else self._workflow.invoke(
                    prepared.user_input,
                    run_context=prepared.run_context,
                    on_event=emit,
                    safe_point=safe_point,
                )
            )
            safe_point()
            if active_model_step is not None:
                self._repository.finish_step(active_model_step, {"status": "COMPLETED"})
                active_model_step = None
            if not self._repository.compare_and_set_status(
                run_id, (RunStatus.RUNNING,), RunStatus.SUCCEEDED
            ):
                self._cancel_if_requested(run_id)
                raise ResumeRejected("RUN_STATE_CONFLICT")
            LOGGER.info("LangGraph workflow trace=%s", json.dumps(result.safe_trace()))
            trace = result.safe_trace()
            trace["run_id"] = run_id
            trace["status"] = "SUCCEEDED"
            trace["latency_ms"] = int((time.monotonic() - started) * 1000)
            trace["memory_written"] = 0
            trace["memory_write_attempted"] = 0
            if prepared.user_input and prepared.run_context.memory_scope is not None:
                try:
                    candidate = self._memory.extract_explicit(prepared.user_input)
                    if candidate:
                        trace["memory_write_attempted"] = 1
                        written = self._memory.upsert(
                            prepared.run_context.memory_scope,
                            key=candidate["key"],
                            kind=candidate["kind"],
                            content=candidate["content"],
                            source_run_id=run_id,
                            confidence=candidate["confidence"],
                        )
                        trace["memory_written"] = int(written)
                except Exception as error:
                    # Memory is secondary; a committed successful Run remains successful.
                    LOGGER.warning("memory write failed failureType=%s", type(error).__name__)
            try:
                self._memory.save_trace(run_id, trace)
            except Exception as error:
                LOGGER.warning("safe trace write failed failureType=%s", type(error).__name__)
            return result
        except RunCancelled:
            self._fail_active_steps(active_model_step, active_tool_steps, "CANCELLED")
            self._repository.compare_and_set_status(
                run_id, (RunStatus.CANCEL_REQUESTED,), RunStatus.CANCELLED
            )
            raise
        except ApprovalRequired:
            self._fail_active_steps(active_model_step, active_tool_steps, "WAITING_APPROVAL")
            if not self._repository.compare_and_set_status(
                run_id, (RunStatus.RUNNING,), RunStatus.WAITING_APPROVAL
            ):
                self._cancel_if_requested(run_id)
                raise ResumeRejected("RUN_STATE_CONFLICT") from None
            raise
        except Exception as error:
            self._fail_active_steps(active_model_step, active_tool_steps, "FAILED")
            code, retryable = classify_failure(error)
            self._repository.compare_and_set_status(
                run_id,
                (RunStatus.RUNNING,),
                RunStatus.FAILED,
                failure_code=code,
                failure_message="agent workflow failed",
                retryable=retryable,
            )
            LOGGER.warning("LangGraph workflow failed failureType=%s", type(error).__name__)
            raise

    def _fail_active_steps(self, model_step, tool_steps, code: str) -> None:
        if model_step is not None:
            self._repository.fail_step(model_step, {"code": code})
        for step_id in tool_steps.values():
            self._repository.fail_step(step_id, {"code": code})

    def _cancel_if_requested(self, run_id: str) -> None:
        run = self._repository.get_run(run_id)
        if run is not None and run.status in {RunStatus.CANCEL_REQUESTED, RunStatus.CANCELLED}:
            raise RunCancelled()

    def request_cancel(self, run_id: str, request_id: str):
        return self._repository.request_cancel(run_id, request_id)

    def trace_for(self, run_id: str) -> dict | None:
        return self._memory.get_trace(run_id)

    def reconcile_interrupted_runs(self) -> tuple[str, ...]:
        return self._repository.reconcile_interrupted_runs()

    def close(self) -> None:
        if self._close_callback:
            self._close_callback()
