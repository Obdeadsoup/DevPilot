"""Versioned dataset runner; real invoker enters through Java HTTP AgentRun."""

import os
from collections.abc import Callable

from devpilot_agent_service.eval.metrics import aggregate, case_metrics
from devpilot_agent_service.eval.schema import EvalCase
from devpilot_agent_service.runtime.redaction import RuntimeRedactor


class EvaluationRunner:
    def __init__(
        self,
        invoker,
        *,
        mode: str,
        precondition_check: Callable[[dict], bool] | None = None,
        judge=None,
    ) -> None:
        if mode not in {"REAL", "FAKE"}:
            raise ValueError("evaluation mode must be REAL or FAKE")
        self.invoker = invoker
        self.mode = mode
        self.precondition_check = precondition_check or (lambda _: True)
        self.judge = judge
        self.redactor = RuntimeRedactor(
            [
                os.getenv("DEVPILOT_EVAL_BEARER_TOKEN", ""),
                os.getenv("DEEPSEEK_API_KEY", ""),
                os.getenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", ""),
                os.getenv("DEVPILOT_EVAL_JUDGE_API_KEY", ""),
            ]
        )

    def run(self, cases: list[EvalCase]) -> tuple[list[dict], dict]:
        rows = self.run_cases(cases)
        return rows, aggregate(rows)

    def run_cases(
        self, cases: list[EvalCase], *, on_case: Callable[[dict], None] | None = None
    ) -> list[dict]:
        rows = []
        for case in cases:
            scenario = case.spec.get("reliability", {}).get("scenario")
            if (
                self.mode == "REAL"
                and scenario
                and scenario not in getattr(self.invoker, "supported_reliability", set())
            ):
                self._record(
                    rows,
                    on_case,
                    {
                        "id": case.id,
                        "mode": self.mode,
                        "status": "SKIPPED_UNSUPPORTED",
                        "skip_reason": "RELIABILITY_DRIVER_REQUIRED",
                        "expected_route": case.spec.get("expected_route"),
                        "metrics": {},
                        "trace": {},
                        "latency_ms": None,
                        "judge": "NOT_RUN",
                    },
                )
                continue
            if not self.precondition_check(case.spec.get("preconditions", {})):
                self._record(
                    rows,
                    on_case,
                    {
                        "id": case.id,
                        "mode": self.mode,
                        "status": "SKIPPED_ENVIRONMENT",
                        "expected_route": case.spec.get("expected_route"),
                        "metrics": {},
                        "trace": {},
                        "latency_ms": None,
                        "judge": "NOT_RUN",
                    },
                )
                continue
            steps = case.steps or ({"query": case.query},)
            observations = []
            for index, step in enumerate(steps):
                spec = {**case.spec, **step, "id": f"{case.id}-{index}"}
                result = self.invoker.invoke(step["query"], spec)
                observations.append(result)
                if result.status in {"FAILED", "MODEL_ERROR", "PROVIDER_ERROR", "TIMEOUT"}:
                    break
            observed = observations[-1]
            safe_answer = str(self.redactor.redact(observed.answer))
            trace = {
                key: observed.trace[key]
                for key in (
                    "planner_route",
                    "planner_confidence",
                    "planner_reason_code",
                    "planner_elapsed_ms",
                    "planner_call_count",
                    "tool_names",
                    "rag_sources",
                    "rag_hits",
                    "tool_arguments",
                    "write_without_approval",
                    "duplicate_side_effect",
                    "unauthorized_tool_execution",
                    "delegation_count",
                    "model_call_count",
                    "tool_call_count",
                    "subagent_model_call_count",
                    "subagent_tool_call_count",
                    "context_summary_used",
                    "memory_recalled",
                    "memory_written",
                    "latency_ms",
                    "status",
                    "memory_write_attempted",
                    "context_omitted_messages",
                    "truncated_tool_result_count",
                )
                if key in observed.trace
            }
            effective_spec = {**case.spec, **steps[len(observations) - 1]}
            metrics = case_metrics(effective_spec, safe_answer, trace, observed.status)
            total_latency = sum(run.latency_ms for run in observations)
            metrics["latency_within_limit"] = (
                total_latency <= case.spec["max_latency_ms"]
                if "max_latency_ms" in case.spec
                else None
            )
            if case.steps:
                checks = []
                for step, run in zip(steps, observations):
                    if "expect_memory_write" in step:
                        checks.append(
                            bool(run.trace["memory_written"]) == step["expect_memory_write"]
                            if run.trace.get("memory_written") is not None
                            else None
                        )
                    if "expect_memory_recall" in step:
                        checks.append(
                            bool(run.trace["memory_recalled"]) == step["expect_memory_recall"]
                            if run.trace.get("memory_recalled") is not None
                            else None
                        )
                expected_count = sum("expect_memory_write" in step for step in steps) + sum(
                    "expect_memory_recall" in step for step in steps
                )
                metrics["expected_memory_recall_accuracy"] = (
                    all(checks)
                    if checks
                    and len(checks) == expected_count
                    and all(value is not None for value in checks)
                    else None
                )
                metrics["cross_run_memory_success"] = (
                    metrics["expected_memory_recall_accuracy"]
                    if any(step.get("expect_memory_recall") for step in steps)
                    else None
                )
                metrics["memory_scope_leakage"] = (
                    bool(observations[-1].trace["memory_recalled"])
                    if "scope_isolation" in case.spec.get("tags", [])
                    and observations[-1].trace.get("memory_recalled") is not None
                    else None
                )
            self._record(
                rows,
                on_case,
                {
                    "id": case.id,
                    "mode": self.mode,
                    "status": observed.status,
                    "failure_kind": observed.failure_kind,
                    "tags": case.spec.get("tags", []),
                    "run_ids": [run.run_id for run in observations],
                    "expected_route": case.spec.get("expected_route"),
                    "trace": trace,
                    "metrics": metrics,
                    "latency_ms": total_latency,
                    "answer": safe_answer[:4000],
                    "judge": (
                        self.judge.judge(
                            steps[len(observations) - 1]["query"],
                            safe_answer,
                            case.spec.get("judge", {}).get("reference_answer", ""),
                        )
                        if self.judge is not None
                        and case.spec.get("judge", {}).get("enabled")
                        and self.mode == "REAL"
                        else "NOT_RUN"
                    ),
                },
            )
        return rows

    def _record(self, rows: list[dict], on_case: Callable[[dict], None] | None, row: dict) -> None:
        safe_row = self.redactor.redact(row)
        if on_case is not None:
            on_case(safe_row)
        rows.append(safe_row)
