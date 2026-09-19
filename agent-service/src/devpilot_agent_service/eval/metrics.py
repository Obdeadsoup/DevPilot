"""Deterministic behavior metrics; missing observations remain unknown, never zero."""

import statistics


def _rate(values):
    return round(sum(values) / len(values), 4) if values else None


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


def case_metrics(spec: dict, answer: str, trace: dict, status: str) -> dict:
    expected = spec.get("expected_route")
    actual = trace.get("planner_route")
    tools = list(trace.get("tool_names") or [])
    expected_tools = set(spec.get("expected_tools", []))
    actual_tools = set(tools)
    sources = list(trace.get("rag_sources") or [])
    expected_sources = list(spec.get("expected_sources", []))
    matches = [source in sources for source in expected_sources]
    ranking = [
        1 / (sources.index(source) + 1) if source in sources else 0
        for source in expected_sources
    ]
    return {
        "run_success": status == "SUCCEEDED",
        "route_correct": actual == expected if expected and actual else None,
        "tool_exact_match": actual_tools == expected_tools if "expected_tools" in spec else None,
        "tool_precision": (
            len(actual_tools & expected_tools) / len(actual_tools) if actual_tools else
            1.0 if not expected_tools else 0.0
        ) if "expected_tools" in spec else None,
        "tool_recall": (
            len(actual_tools & expected_tools) / len(expected_tools)
            if expected_tools else 1.0
        ) if "expected_tools" in spec else None,
        "forbidden_tool_violation": bool(
            actual_tools & set(spec.get("forbidden_tools", []))
        ),
        "source_recall_at_k": _rate(matches) if expected_sources else None,
        "source_mrr": _rate(ranking) if expected_sources else None,
        "citation_present": (
            any(source.lower() in answer.lower() for source in expected_sources)
            if expected_sources else None
        ),
        "rag_empty_result": (
            not sources if "knowledge.search" in tools else None
        ),
        "delegation_match": (
            bool(trace.get("delegation_count")) == bool(spec["expected_delegation"])
            if "expected_delegation" in spec else None
        ),
        "answer_assertions": assertions(answer, spec.get("answer_assertions", {})),
    }


def aggregate(results: list[dict]) -> dict:
    evaluated = [r for r in results if r["status"] != "SKIPPED_ENVIRONMENT"]
    metric_keys = (
        "run_success", "route_correct", "tool_exact_match", "tool_precision",
        "tool_recall", "forbidden_tool_violation", "source_recall_at_k",
        "source_mrr", "citation_present", "rag_empty_result", "delegation_match",
        "latency_within_limit",
    )
    rates = {
        key: _rate([r["metrics"][key] for r in evaluated if r["metrics"].get(key) is not None])
        for key in metric_keys
    }
    confusion = {}
    for row in evaluated:
        expected = row.get("expected_route")
        actual = row.get("trace", {}).get("planner_route")
        if expected and actual:
            confusion.setdefault(expected, {})
            confusion[expected][actual] = confusion[expected].get(actual, 0) + 1
    latencies = [r["latency_ms"] for r in evaluated if r.get("latency_ms") is not None]
    planner_latencies = [
        r["trace"]["planner_elapsed_ms"] for r in evaluated
        if isinstance(r.get("trace", {}).get("planner_elapsed_ms"), (int, float))
    ]
    for name, values in (("latency", latencies), ("planner_latency", planner_latencies)):
        rates[name + "_p50_ms"] = _percentile(values, .50)
        rates[name + "_p95_ms"] = _percentile(values, .95)
    for field in ("model_call_count", "tool_call_count", "delegation_count",
                  "memory_recalled", "memory_written"):
        values = [
            r["trace"][field] for r in evaluated
            if isinstance(r.get("trace", {}).get(field), (int, float))
        ]
        rates["average_" + field] = round(statistics.mean(values), 3) if values else None
    rates["route_confusion_matrix"] = confusion
    observed_traces = [r.get("trace", {}) for r in evaluated]
    reasons = [trace["planner_reason_code"] for trace in observed_traces
               if trace.get("planner_reason_code")]
    fallback = {"INVALID_OUTPUT", "LOW_CONFIDENCE", "PROVIDER_ERROR", "TIMEOUT", "BUSY"}
    rates["planner_fallback_rate"] = _rate([reason in fallback for reason in reasons])
    rates["planner_error_rate"] = _rate([
        reason in {"INVALID_OUTPUT", "PROVIDER_ERROR", "TIMEOUT", "BUSY"}
        for reason in reasons
    ])
    confidences = [
        trace["planner_confidence"] for trace in observed_traces
        if isinstance(trace.get("planner_confidence"), (int, float))
    ]
    rates["low_confidence_rate"] = _rate([value < .5 for value in confidences])
    rates["context_summary_used_rate"] = _rate([
        bool(trace["context_summary_used"]) for trace in observed_traces
        if "context_summary_used" in trace
    ])
    rates["memory_recall_hit_rate"] = _rate([
        trace["memory_recalled"] > 0 for trace in observed_traces
        if isinstance(trace.get("memory_recalled"), int)
    ])
    attempts = [trace for trace in observed_traces if trace.get("memory_write_attempted")]
    rates["duplicate_memory_suppression_rate"] = _rate([
        not bool(trace.get("memory_written")) for trace in attempts
    ])
    scenarios = [
        row["metrics"]["cross_run_memory_success"] for row in evaluated
        if "cross_run_memory_success" in row["metrics"]
    ]
    rates["cross_run_recall_success_rate"] = _rate(scenarios)
    memory_accuracy = [
        row["metrics"]["expected_memory_recall_accuracy"] for row in evaluated
        if "expected_memory_recall_accuracy" in row["metrics"]
    ]
    rates["expected_memory_recall_accuracy"] = _rate(memory_accuracy)
    rates["tool_execution_success_rate"] = None
    rates["context_budget_failure_rate"] = None
    for field in ("context_omitted_messages", "truncated_tool_result_count",
                  "subagent_model_call_count", "subagent_tool_call_count"):
        values = [
            trace[field] for trace in observed_traces
            if isinstance(trace.get(field), (int, float))
        ]
        rates["average_" + field] = round(statistics.mean(values), 3) if values else None
    rates["cases_total"] = len(results)
    rates["cases_evaluated"] = len(evaluated)
    rates["cases_skipped_environment"] = len(results) - len(evaluated)
    rates["answer_assertion_pass_rate"] = _rate([
        value for row in evaluated for value in row["metrics"]["answer_assertions"].values()
    ])
    return rates
