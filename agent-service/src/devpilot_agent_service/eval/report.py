"""Write explicit-mode machine and human readable reports."""

import json
import os
import tempfile
from pathlib import Path


def _atomic_write(path: Path, content: str) -> None:
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


class RunArtifacts:
    """Keep completed case observations durable before any aggregation or report work."""

    def __init__(self, output_dir: str | Path, metadata: dict, redactor) -> None:
        self.target = Path(output_dir)
        self.target.mkdir(parents=True, exist_ok=True)
        self.partial_path = self.target / "cases.partial.jsonl"
        self.metadata_path = self.target / "metadata.json"
        for path in (self.partial_path, self.metadata_path, self.target / "cases.jsonl"):
            if path.exists():
                raise FileExistsError(
                    f"Eval output already exists: {path}; choose a new output directory"
                )
        self.redactor = redactor
        self.metadata = self.redactor.redact(metadata)
        _atomic_write(self.metadata_path, _json(self.metadata))
        with self.partial_path.open("x", encoding="utf-8") as stream:
            stream.flush()
            os.fsync(stream.fileno())

    def append_case(self, row: dict) -> None:
        try:
            line = json.dumps(self.redactor.redact(row), ensure_ascii=False) + "\n"
            with self.partial_path.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(line)
                stream.flush()
                os.fsync(stream.fileno())
        except (OSError, TypeError, ValueError) as error:
            raise RuntimeError(
                f"Failed to persist Eval case {row.get('id')} to {self.partial_path}: "
                f"{type(error).__name__}"
            ) from None

    def record_error(self, phase: str, error: Exception, finished_at: str) -> None:
        detail = self.redactor.redact(
            {"phase": phase, "type": type(error).__name__, "message": str(error)}
        )
        self.metadata["finished_at"] = finished_at
        self.metadata["eval_error"] = detail
        _atomic_write(self.metadata_path, _json(self.metadata))
        if phase == "aggregation":
            _atomic_write(self.target / "aggregation-error.json", _json(detail))


def write_report(
    output_dir: str | Path,
    rows: list[dict],
    summary: dict,
    metadata: dict,
    *,
    partial_path: Path | None = None,
) -> None:
    target = Path(output_dir)
    target.mkdir(parents=True, exist_ok=True)
    cases = (
        partial_path.read_text(encoding="utf-8")
        if partial_path is not None
        else "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    )
    _atomic_write(target / "cases.jsonl", cases)
    _atomic_write(target / "summary.json", _json(summary))
    _atomic_write(target / "metadata.json", _json(metadata))
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
        f"| {key} | {json.dumps(value, ensure_ascii=False)} |" for key, value in summary.items()
    )
    lines.extend(["", "## Cases", "", "| ID | Status | Route |", "| --- | --- | --- |"])
    lines.extend(
        f"| {row['id']} | {row['status']} | "
        f"{row.get('trace', {}).get('planner_route', 'unknown')} |"
        for row in rows
    )
    _atomic_write(target / "report.md", "\n".join(lines) + "\n")
