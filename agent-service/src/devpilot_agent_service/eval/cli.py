"""Validate datasets and run explicitly marked fake or real E2E evaluations."""

import argparse
import json
import os
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from devpilot_agent_service.eval.dataset import load_dataset, validate_v2_splits
from devpilot_agent_service.eval.failure import write_failure_analysis
from devpilot_agent_service.eval.invoker import FakeInvoker, JavaHttpInvoker
from devpilot_agent_service.eval.judge import StructuredJudge
from devpilot_agent_service.eval.metrics import aggregate
from devpilot_agent_service.eval.report import RunArtifacts, write_report
from devpilot_agent_service.eval.runner import EvaluationRunner
from devpilot_agent_service.eval.seed import DevPilotApi, load_manifest, seed, write_seed_report


def _git_sha() -> str:
    configured_sha = os.getenv("DEVPILOT_EVAL_GIT_SHA", "").strip()
    if configured_sha:
        return configured_sha

    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=False
        )
    except OSError:
        return "unavailable"

    sha = result.stdout.strip() if result.stdout else ""
    return sha if result.returncode == 0 and sha else "unavailable"


def _precondition(value: dict) -> bool:
    root = Path(__file__).resolve().parents[4]
    scope = value.get("required_scope")
    scope_ready = scope not in {"OTHER_PROJECT", "OTHER_WORKSPACE"} or bool(
        os.getenv("DEVPILOT_EVAL_OTHER_PROJECT_ID")
        if scope == "OTHER_PROJECT"
        else os.getenv("DEVPILOT_EVAL_OTHER_WORKSPACE_ID")
        and os.getenv("DEVPILOT_EVAL_OTHER_WORKSPACE_PROJECT_ID")
    )
    return scope_ready and all(
        (root / name).is_file() for name in value.get("required_knowledge_files", [])
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="devpilot-agent-eval")
    sub = parser.add_subparsers(dest="action", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--dataset", required=True)
    validate.add_argument("--holdout")
    seed_parser = sub.add_parser("seed")
    seed_parser.add_argument(
        "--manifest",
        default=str(Path(__file__).resolve().parents[3] / "evals/fixtures/manifest_v2.json"),
    )
    seed_parser.add_argument(
        "--output-dir", default=str(Path(__file__).resolve().parents[3] / "evals/results/seed")
    )
    seed_parser.add_argument("--wait-seconds", type=int, default=300)
    run = sub.add_parser("run")
    run.add_argument("--dataset", required=True)
    run.add_argument("--output-dir", required=True)
    run.add_argument("--mode", choices=("REAL", "FAKE"), default="REAL")
    run.add_argument("--case-id")
    run.add_argument("--tag")
    run.add_argument("--limit", type=int)
    run.add_argument("--no-judge", action="store_true")
    run.add_argument(
        "--seed-report",
        default=str(Path(__file__).resolve().parents[3] / "evals/results/seed/seed-report.json"),
    )
    recover = sub.add_parser("recover")
    recover.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)
    if args.action == "seed":
        report = seed(
            load_manifest(Path(args.manifest)), DevPilotApi(), wait_seconds=args.wait_seconds
        )
        write_seed_report(report, Path(args.output_dir))
        print(
            json.dumps(
                {
                    "fixture_version": report["fixture_version"],
                    "created": report["created"],
                    "reused": report["reused"],
                    "updated": report["updated"],
                    "failed": report["failed"],
                    "ready": report["ready_count"],
                },
                ensure_ascii=False,
            )
        )
        return 1 if report["failed"] else 0
    if args.action == "recover":
        output_dir = Path(args.output_dir)
        partial_path = output_dir / "cases.partial.jsonl"
        if (output_dir / "cases.jsonl").exists():
            raise FileExistsError("formal cases.jsonl already exists; recovery would overwrite it")
        metadata = json.loads((output_dir / "metadata.json").read_text(encoding="utf-8"))
        rows = [json.loads(line) for line in partial_path.read_text(encoding="utf-8").splitlines()]
        summary = aggregate(rows)
        if "eval_error" in metadata:
            metadata["recovered_error"] = metadata.pop("eval_error")
        metadata["recovered_at"] = datetime.now(UTC).isoformat()
        metadata["recovered_from"] = partial_path.name
        write_report(output_dir, rows, summary, metadata, partial_path=partial_path)
        write_failure_analysis(output_dir, rows, metadata)
        print(json.dumps({"recovered_cases": len(rows), "output_dir": str(output_dir)}))
        return 0
    cases, digest = load_dataset(args.dataset)
    if args.action == "validate":
        result = (
            {"valid": True, **validate_v2_splits(args.dataset, args.holdout)}
            if args.holdout
            else {"valid": True, "cases": len(cases), "sha256": digest}
        )
        print(json.dumps(result))
        return 0
    if args.case_id:
        cases = [case for case in cases if case.id == args.case_id]
    if args.tag:
        cases = [case for case in cases if args.tag in case.spec.get("tags", [])]
    if args.limit is not None:
        if args.limit < 1:
            raise ValueError("--limit must be positive")
        cases = cases[: args.limit]
    if not cases:
        raise ValueError("no evaluation cases selected")
    fixture_version = cases[0].spec.get("fixture_version")
    if any(case.spec.get("fixture_version") != fixture_version for case in cases):
        raise ValueError("mixed fixture versions in dataset")
    started = datetime.now(UTC).isoformat()
    invoker = FakeInvoker() if args.mode == "FAKE" else JavaHttpInvoker()
    judge = None
    if args.mode == "REAL":
        if fixture_version:
            seed_report = json.loads(Path(args.seed_report).read_text(encoding="utf-8"))
            if (
                seed_report.get("fixture_version") != fixture_version
                or seed_report.get("workspace_id") != invoker.workspace_id
                or seed_report.get("project_id") != invoker.project_id
                or seed_report.get("failed") != 0
                or seed_report.get("ready_count") != 12
            ):
                raise ValueError("REAL V2 run requires a successful matching seed report")
        invoker.preflight()
        if not args.no_judge and os.getenv("DEVPILOT_EVAL_JUDGE_API_KEY"):
            judge = StructuredJudge()
    metadata = {
        "mode": args.mode,
        "synthetic_observations": args.mode == "FAKE",
        "dataset_name": Path(args.dataset).name,
        "dataset_version": cases[0].spec["schema_version"],
        "fixture_version": fixture_version,
        "dataset_hash": digest,
        "git_sha": _git_sha(),
        "generation_model": os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
        if args.mode == "REAL"
        else "FAKE",
        "planner_model": os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
        if args.mode == "REAL"
        else "FAKE",
        "temperature": None,
        "rag_top_k": 5,
        "judge_model": judge.model if judge else None,
        "judge_model_same_as_generation": (
            judge.model == os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash") if judge else False
        ),
        "workspace_id": getattr(invoker, "workspace_id", None),
        "project_id": getattr(invoker, "project_id", None),
        "repository_binding_id": getattr(invoker, "binding", None),
        "branch_name": getattr(invoker, "branch", None),
        "runtime_db_path": getattr(invoker, "runtime_db", None),
        "started_at": started,
    }
    runner = EvaluationRunner(
        invoker, mode=args.mode, precondition_check=_precondition, judge=judge
    )
    metadata = runner.redactor.redact(metadata)
    try:
        artifacts = RunArtifacts(args.output_dir, metadata, runner.redactor)
        print(
            json.dumps(
                {
                    "preflight": "OK",
                    "mode": args.mode,
                    "git_sha": metadata["git_sha"],
                    "dataset_hash": digest,
                    "java_reachable": args.mode == "REAL",
                    "workspace_id": metadata["workspace_id"],
                    "project_id": metadata["project_id"],
                    "generation_model": metadata["generation_model"],
                    "judge_model": metadata["judge_model"],
                    "runtime_db_path": metadata["runtime_db_path"],
                },
                ensure_ascii=False,
            )
        )
        phase = "case_execution"
        try:
            rows = runner.run_cases(cases, on_case=artifacts.append_case)
            phase = "aggregation"
            summary = aggregate(rows)
            phase = "report"
            metadata["finished_at"] = datetime.now(UTC).isoformat()
            write_report(
                args.output_dir, rows, summary, metadata, partial_path=artifacts.partial_path
            )
            write_failure_analysis(args.output_dir, rows, metadata)
        except Exception as error:
            artifacts.record_error(phase, error, datetime.now(UTC).isoformat())
            raise
    finally:
        if hasattr(invoker, "close"):
            invoker.close()
    print(
        json.dumps(
            {
                "mode": args.mode,
                "cases": len(rows),
                "output_dir": args.output_dir,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
