"""Classify deterministic V2 failures without pretending to know model reasoning."""

from pathlib import Path


def classify(row: dict) -> list[str]:
    if row.get("status") == "SKIPPED_UNSUPPORTED":
        return [row.get("skip_reason", "RELIABILITY_DRIVER_REQUIRED")]
    if row.get("status") == "SKIPPED_ENVIRONMENT":
        return ["ENVIRONMENT_ERROR"]
    failures = []
    if row.get("status") in {"FAILED", "MODEL_ERROR", "PROVIDER_ERROR", "TIMEOUT"}:
        failures.append(row.get("failure_kind") or row["status"])
    metrics = row.get("metrics", {})
    checks = (
        ("route_correct", "ROUTING_ERROR"),
        ("tool_exact_match", "TOOL_SELECTION_ERROR"),
        ("tool_argument_accuracy", "TOOL_ARGUMENT_ERROR"),
        ("source_recall_at_k", "RETRIEVAL_MISS"),
        ("evidence_recall_at_k", "RETRIEVAL_MISS"),
        ("citation_present", "GENERATION_ERROR"),
        ("memory_write_correct", "MEMORY_ERROR"),
        ("expected_memory_recall_accuracy", "MEMORY_ERROR"),
    )
    for key, label in checks:
        value = metrics.get(key)
        if value is False or (isinstance(value, (int, float)) and value < 1):
            if label not in failures:
                failures.append(label)
    if metrics.get("forbidden_tool_violation"):
        failures.append("TOOL_SELECTION_ERROR")
    if any(
        metrics.get(key)
        for key in (
            "write_without_approval",
            "duplicate_side_effect",
            "unauthorized_tool_execution",
        )
    ):
        failures.append("RELIABILITY_ERROR")
    if any(value is False for value in metrics.get("answer_assertions", {}).values()):
        failures.append("GENERATION_ERROR")
    return failures


def write_failure_analysis(output_dir: str | Path, rows: list[dict], metadata: dict) -> None:
    lines = [
        "# Failure analysis",
        "",
        f"Mode: **{metadata['mode']}**",
        "",
        "Automatic labels are symptoms, not root-cause proof. Inspect safe traces and "
        "the actual run before changing prompts or retrieval settings.",
        "",
        "| Case | Labels |",
        "| --- | --- |",
    ]
    for row in rows:
        labels = classify(row)
        if labels:
            lines.append(f"| {row['id']} | {', '.join(labels)} |")
    lines.extend(
        [
            "",
            "## Manual review",
            "",
            "Check QUERY_REWRITE_ERROR, RERANK_ERROR and CONTEXT_LOSS using "
            "retrieval diagnostics and controlled comparisons; the current safe trace "
            "does not prove these causes. For HITL, unknown hard gates require "
            "the Java proposal workflow integration tests.",
            "",
        ]
    )
    (Path(output_dir) / "failure-analysis.md").write_text("\n".join(lines), encoding="utf-8")
