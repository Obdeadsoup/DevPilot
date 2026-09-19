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
        self.redactor = RuntimeRedactor([
            os.getenv("DEVPILOT_EVAL_BEARER_TOKEN", ""),
            os.getenv("DEEPSEEK_API_KEY", ""),
            os.getenv("DEVPILOT_AGENT_TOOL_SERVICE_KEY", ""),
            os.getenv("DEVPILOT_EVAL_JUDGE_API_KEY", ""),
        ])

    def run(self, cases: list[EvalCase]) -> tuple[list[dict], dict]:
        rows = []
        for case in cases:
            if not self.precondition_check(case.spec.get("preconditions", {})):
                rows.append({
                    "id": case.id, "status": "SKIPPED_ENVIRONMENT",
                    "expected_route": case.spec.get("expected_route"),
                    "metrics": {}, "trace": {}, "latency_ms": None,
                    "judge": "NOT_RUN",
                })
                continue
            steps = case.steps or ({"query": case.query},)
            observations = []
            for index, step in enumerate(steps):
                spec = {**case.spec, **step, "id": f"{case.id}-{index}"}
                result = self.invoker.invoke(step["query"], spec)
                observations.append(result)
            observed = observations[-1]
            safe_answer = str(self.redactor.redact(observed.answer))
            trace = {
                key: observed.trace[key]
                for key in (
                    "planner_route", "planner_confidence", "planner_reason_code",
                    "planner_elapsed_ms", "planner_call_count", "tool_names",
                    "rag_sources", "delegation_count", "model_call_count",
                    "tool_call_count", "subagent_model_call_count",
                    "subagent_tool_call_count", "context_summary_used",
                    "memory_recalled", "memory_written", "latency_ms", "status",
                    "memory_write_attempted", "context_omitted_messages",
                    "truncated_tool_result_count",
                )
                if key in observed.trace
            }
            effective_spec = {**case.spec, **steps[-1]}
            metrics = case_metrics(effective_spec, safe_answer, trace, observed.status)
            total_latency = sum(run.latency_ms for run in observations)
            metrics["latency_within_limit"] = (
                total_latency <= case.spec["max_latency_ms"]
                if "max_latency_ms" in case.spec else None
            )
            if case.steps:
                metrics["cross_run_memory_success"] = (
                    len({run.run_id for run in observations}) == len(observations)
                    and bool(observations[0].trace.get("memory_written"))
                    and bool(trace.get("memory_recalled"))
                )
                metrics["expected_memory_recall_accuracy"] = (
                    metrics["cross_run_memory_success"]
                    and all(metrics["answer_assertions"].values())
                )
            rows.append({
                "id": case.id, "mode": self.mode, "status": observed.status,
                "run_ids": [run.run_id for run in observations],
                "expected_route": case.spec.get("expected_route"),
                "trace": trace,
                "metrics": metrics,
                "latency_ms": total_latency,
                "answer": safe_answer[:4000],
                "judge": (
                    self.judge.judge(
                        steps[-1]["query"], safe_answer,
                        case.spec.get("judge", {}).get("reference_answer", ""),
                    )
                    if self.judge is not None
                    and case.spec.get("judge", {}).get("enabled")
                    and self.mode == "REAL"
                    else "NOT_RUN"
                ),
            })
        return rows, aggregate(rows)
