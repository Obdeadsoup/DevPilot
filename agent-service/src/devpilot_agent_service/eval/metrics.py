"""Deterministic behavior metrics; missing observations remain unknown, never zero."""

import math
import statistics


def _observed(values):
    return [
        value for value in values if isinstance(value, (bool, int, float)) and math.isfinite(value)
    ]


def _rate(values):
    observed = _observed(values)
    return round(sum(observed) / len(observed), 4) if observed else None


def _percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * percent)))
    return ordered[index]


def assertions(answer: str, rules: dict) -> dict:
    checks = {}
    for term in rules.get("must_include_all", []):
        checks[f"include:{term}"] = term.lower() in answer.lower()
    choices = rules.get("must_include_any", [])
    if choices:
        checks["include_any"] = any(term.lower() in answer.lower() for term in choices)
    for term in rules.get("must_not_include", []):
        checks[f"exclude:{term}"] = term.lower() not in answer.lower()
    citation = rules.get("required_source_citation")
    if citation:
        checks["source_citation"] = citation.lower() in answer.lower()
    return checks


def tool_argument_accuracy(expected: dict, observed: list[dict] | None):
    if not expected or observed is None:
        return None
    checks = []
    for name, fields in expected.items():
        calls = [item.get("args", {}) for item in observed if item.get("name") == name]
        for field, rule in fields.items():
            checks.append(
                any(
                    type(args.get(field)) is int
                    and ("min" not in rule or args[field] >= rule["min"])
                    and ("max" not in rule or args[field] <= rule["max"])
                    and ("equals" not in rule or args[field] == rule["equals"])
                    for args in calls
                )
            )
    return _rate(checks)


def evidence_metrics(expected: list[dict], hits: list[dict] | None):
    if not expected or hits is None:
        return None, None, None
    ranks = []
    for item in expected:
        rank = next(
            (
                index
                for index, hit in enumerate(hits, 1)
                if hit.get("source") == item["source"]
                and ("chunk_id" not in item or hit.get("chunk_id") == item["chunk_id"])
            ),
            None,
        )
        ranks.append(rank)
    # The current ingestion pipeline does not emit stable section IDs. Section labels
    # remain manifest provenance, not a fabricated exact-match score.
    section_score = (
        None
        if any("section" in item and "chunk_id" not in item for item in expected)
        else _rate([rank is not None for rank in ranks])
    )
    return (
        _rate([rank is not None for rank in ranks]),
        _rate([1 / rank if rank is not None else 0 for rank in ranks]),
        section_score,
    )


def case_metrics(spec: dict, answer: str, trace: dict, status: str) -> dict:
    expected = spec.get("expected_route")
    actual = trace.get("planner_route")
    tools_observed = trace.get("tool_names") is not None
    tools = list(trace.get("tool_names") or [])
    expected_tools = set(spec.get("expected_tools", []))
    actual_tools = set(tools)
    sources = list(trace.get("rag_sources") or [])
    sources_observed = trace.get("rag_sources") is not None
    expected_sources = list(spec.get("expected_sources", []))
    matches = [source in sources for source in expected_sources]
    ranking = [
        1 / (sources.index(source) + 1) if source in sources else 0 for source in expected_sources
    ]
    evidence_recall, evidence_mrr, section_accuracy = evidence_metrics(
        spec.get("expected_evidence", []), trace.get("rag_hits")
    )
    expected_memory_write = spec.get("expected_memory_write")
    observed_memory_write = trace.get("memory_written")
    reliability = spec.get("reliability", {})
    answer_observed = bool(answer) or status == "SUCCEEDED"
    return {
        "run_success": status not in {"FAILED", "MODEL_ERROR", "PROVIDER_ERROR", "TIMEOUT"}
        and status == spec.get("expected_status", "SUCCEEDED"),
        "route_correct": actual == expected if expected and actual else None,
        "tool_exact_match": (
            actual_tools == expected_tools if "expected_tools" in spec and tools_observed else None
        ),
        "tool_precision": (
            len(actual_tools & expected_tools) / len(actual_tools)
            if actual_tools
            else 1.0
            if not expected_tools
            else 0.0
        )
        if "expected_tools" in spec and tools_observed
        else None,
        "tool_recall": (
            len(actual_tools & expected_tools) / len(expected_tools) if expected_tools else 1.0
        )
        if "expected_tools" in spec and tools_observed
        else None,
        "forbidden_tool_violation": (
            bool(actual_tools & set(spec.get("forbidden_tools", []))) if tools_observed else None
        ),
        "source_recall_at_k": _rate(matches) if expected_sources and sources_observed else None,
        "source_mrr": _rate(ranking) if expected_sources and sources_observed else None,
        "evidence_recall_at_k": evidence_recall,
        "evidence_mrr": evidence_mrr,
        "evidence_section_accuracy": section_accuracy,
        "tool_argument_accuracy": tool_argument_accuracy(
            spec.get("expected_tool_args", {}), trace.get("tool_arguments")
        ),
        "memory_write_correct": (
            bool(observed_memory_write) == expected_memory_write
            if expected_memory_write is not None and observed_memory_write is not None
            else None
        ),
        "memory_false_write": (
            bool(observed_memory_write)
            if expected_memory_write is False and observed_memory_write is not None
            else None
        ),
        "write_without_approval": (
            bool(trace["write_without_approval"])
            if reliability.get("write_without_approval") is False
            and trace.get("write_without_approval") is not None
            else None
        ),
        "duplicate_side_effect": (
            bool(trace["duplicate_side_effect"])
            if reliability.get("duplicate_side_effect") is False
            and trace.get("duplicate_side_effect") is not None
            else None
        ),
        "unauthorized_tool_execution": (
            bool(trace["unauthorized_tool_execution"])
            if reliability.get("unauthorized_tool_execution") is False
            and trace.get("unauthorized_tool_execution") is not None
            else None
        ),
        "citation_present": (
            any(source.lower() in answer.lower() for source in expected_sources)
            if expected_sources and answer_observed
            else None
        ),
        "rag_empty_result": (
            not sources if "knowledge.search" in tools and sources_observed else None
        ),
        "delegation_match": (
            bool(trace.get("delegation_count")) == bool(spec["expected_delegation"])
            if "expected_delegation" in spec and trace.get("delegation_count") is not None
            else None
        ),
        "answer_assertions": (
            assertions(answer, spec.get("answer_assertions", {})) if answer_observed else {}
        ),
    }


def aggregate(results: list[dict]) -> dict:
    evaluated = [r for r in results if not r["status"].startswith("SKIPPED_")]
    metric_keys = (
        "run_success",
        "route_correct",
        "tool_exact_match",
        "tool_precision",
        "tool_recall",
        "forbidden_tool_violation",
        "source_recall_at_k",
        "source_mrr",
        "evidence_recall_at_k",
        "evidence_mrr",
        "evidence_section_accuracy",
        "tool_argument_accuracy",
        "memory_write_correct",
        "memory_false_write",
        "write_without_approval",
        "duplicate_side_effect",
        "unauthorized_tool_execution",
        "citation_present",
        "rag_empty_result",
        "delegation_match",
        "latency_within_limit",
    )
    rates = {}

    def add_rate(name, values):
        observed = _observed(values)
        rates[name] = _rate(observed)
        rates[name + "_observed"] = len(observed)

    for key in metric_keys:
        add_rate(key, [r["metrics"].get(key) for r in evaluated])
    confusion = {}
    for row in evaluated:
        expected = row.get("expected_route")
        actual = row.get("trace", {}).get("planner_route")
        if expected and actual:
            confusion.setdefault(expected, {})
            confusion[expected][actual] = confusion[expected].get(actual, 0) + 1
    latencies = [r["latency_ms"] for r in evaluated if r.get("latency_ms") is not None]
    planner_latencies = [
        r["trace"]["planner_elapsed_ms"]
        for r in evaluated
        if isinstance(r.get("trace", {}).get("planner_elapsed_ms"), (int, float))
    ]
    for name, values in (("latency", latencies), ("planner_latency", planner_latencies)):
        rates[name + "_p50_ms"] = _percentile(values, 0.50)
        rates[name + "_p95_ms"] = _percentile(values, 0.95)
    for field in (
        "model_call_count",
        "tool_call_count",
        "delegation_count",
        "memory_recalled",
        "memory_written",
    ):
        values = [
            r["trace"][field]
            for r in evaluated
            if isinstance(r.get("trace", {}).get(field), (int, float))
        ]
        rates["average_" + field] = round(statistics.mean(values), 3) if values else None
    rates["route_confusion_matrix"] = confusion
    observed_traces = [r.get("trace", {}) for r in evaluated]
    reasons = [
        trace["planner_reason_code"]
        for trace in observed_traces
        if trace.get("planner_reason_code")
    ]
    fallback = {"INVALID_OUTPUT", "LOW_CONFIDENCE", "PROVIDER_ERROR", "TIMEOUT", "BUSY"}
    add_rate("planner_fallback_rate", [reason in fallback for reason in reasons])
    add_rate(
        "planner_error_rate",
        [reason in {"INVALID_OUTPUT", "PROVIDER_ERROR", "TIMEOUT", "BUSY"} for reason in reasons],
    )
    confidences = [
        trace["planner_confidence"]
        for trace in observed_traces
        if isinstance(trace.get("planner_confidence"), (int, float))
    ]
    add_rate("low_confidence_rate", [value < 0.5 for value in confidences])
    add_rate(
        "context_summary_used_rate",
        [
            bool(trace["context_summary_used"])
            for trace in observed_traces
            if trace.get("context_summary_used") is not None
        ],
    )
    add_rate(
        "memory_recall_hit_rate",
        [
            trace["memory_recalled"] > 0
            for trace in observed_traces
            if isinstance(trace.get("memory_recalled"), int)
        ],
    )
    attempts = [trace for trace in observed_traces if trace.get("memory_write_attempted")]
    add_rate(
        "duplicate_memory_suppression_rate",
        [
            not bool(trace["memory_written"])
            for trace in attempts
            if trace.get("memory_written") is not None
        ],
    )
    scenarios = [
        row["metrics"]["cross_run_memory_success"]
        for row in evaluated
        if "cross_run_memory_success" in row["metrics"]
    ]
    add_rate("cross_run_recall_success_rate", scenarios)
    memory_accuracy = [
        row["metrics"]["expected_memory_recall_accuracy"]
        for row in evaluated
        if "expected_memory_recall_accuracy" in row["metrics"]
    ]
    add_rate("expected_memory_recall_accuracy", memory_accuracy)
    rates["memory_false_write_rate"] = rates["memory_false_write"]
    rates["memory_false_write_rate_observed"] = rates["memory_false_write_observed"]
    add_rate(
        "memory_scope_leakage_rate",
        [row["metrics"].get("memory_scope_leakage") for row in evaluated],
    )
    rates["hard_gates_observed"] = all(
        rates[key] is not None
        for key in (
            "write_without_approval",
            "duplicate_side_effect",
            "unauthorized_tool_execution",
        )
    )
    rates["hard_gates_pass"] = (
        all(
            rates[key] == 0
            for key in (
                "write_without_approval",
                "duplicate_side_effect",
                "unauthorized_tool_execution",
            )
        )
        if rates["hard_gates_observed"]
        else None
    )
    add_rate("tool_execution_success_rate", [])
    add_rate("context_budget_failure_rate", [])
    for field in (
        "context_omitted_messages",
        "truncated_tool_result_count",
        "subagent_model_call_count",
        "subagent_tool_call_count",
    ):
        values = [
            trace[field] for trace in observed_traces if isinstance(trace.get(field), (int, float))
        ]
        rates["average_" + field] = round(statistics.mean(values), 3) if values else None
    rates["cases_total"] = len(results)
    rates["cases_evaluated"] = len(evaluated)
    rates["cases_skipped"] = len(results) - len(evaluated)
    rates["cases_skipped_environment"] = sum(
        row["status"] == "SKIPPED_ENVIRONMENT" for row in results
    )
    rates["cases_skipped_unsupported"] = sum(
        row["status"] == "SKIPPED_UNSUPPORTED" for row in results
    )
    rates["cases_succeeded"] = sum(row["metrics"].get("run_success") is True for row in evaluated)
    rates["cases_failed"] = sum(row["metrics"].get("run_success") is False for row in evaluated)
    rates["cases_model_error"] = sum(
        row["status"] == "MODEL_ERROR" or row.get("failure_kind") == "MODEL_ERROR"
        for row in evaluated
    )
    rates["cases_timeout"] = sum(
        row["status"] == "TIMEOUT" or row.get("failure_kind") in {"TIMEOUT", "DEADLINE_EXCEEDED"}
        for row in evaluated
    )
    add_rate(
        "answer_assertion_pass_rate",
        [value for row in evaluated for value in row["metrics"]["answer_assertions"].values()],
    )
    return rates
