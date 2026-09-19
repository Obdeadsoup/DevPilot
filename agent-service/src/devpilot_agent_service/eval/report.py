"""Write explicit-mode machine and human readable reports."""

import json
from pathlib import Path


def write_report(output_dir: str | Path, rows: list[dict], summary: dict, metadata: dict) -> None:
    target = Path(output_dir)
    target.mkdir(parents=True, exist_ok=True)
    (target / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (target / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (target / "cases.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    lines = [
        "# DevPilot Agent Evaluation",
        "",
        f"Mode: **{metadata['mode']}**",
        "",
        (
            "**Synthetic evaluator fixture; these scores do not measure Agent quality.**"
            if metadata.get("synthetic_observations")
            else "Real Java AgentRun observations."
        ),
        "",
        f"Dataset SHA-256: `{metadata['dataset_hash']}`",
        "",
        "| Metric | Value |",
        "| --- | --- |",
    ]
    lines.extend(
        f"| {key} | {json.dumps(value, ensure_ascii=False)} |"
        for key, value in summary.items()
    )
    lines.extend(["", "## Cases", "", "| ID | Status | Route |", "| --- | --- | --- |"])
    lines.extend(
        f"| {row['id']} | {row['status']} | "
        f"{row.get('trace', {}).get('planner_route', 'unknown')} |"
        for row in rows
    )
    (target / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
