"""Validate datasets and run explicitly marked fake or real E2E evaluations."""

import argparse
import json
import os
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from devpilot_agent_service.eval.dataset import load_dataset
from devpilot_agent_service.eval.invoker import FakeInvoker, JavaHttpInvoker
from devpilot_agent_service.eval.judge import StructuredJudge
from devpilot_agent_service.eval.report import write_report
from devpilot_agent_service.eval.runner import EvaluationRunner


def _git_sha() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else "unavailable"


def _precondition(value: dict) -> bool:
    root = Path(__file__).resolve().parents[4]
    return all(
        (root / name).is_file()
        for name in value.get("required_knowledge_files", [])
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="devpilot-agent-eval")
    sub = parser.add_subparsers(dest="action", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--dataset", required=True)
    run = sub.add_parser("run")
    run.add_argument("--dataset", required=True)
    run.add_argument("--output-dir", required=True)
    run.add_argument("--mode", choices=("REAL", "FAKE"), default="REAL")
    run.add_argument("--case-id")
    run.add_argument("--tag")
    run.add_argument("--limit", type=int)
    run.add_argument("--no-judge", action="store_true")
    args = parser.parse_args(argv)
    cases, digest = load_dataset(args.dataset)
    if args.action == "validate":
        print(json.dumps({"valid": True, "cases": len(cases), "sha256": digest}))
        return 0
    if args.case_id:
        cases = [case for case in cases if case.id == args.case_id]
    if args.tag:
        cases = [case for case in cases if args.tag in case.spec.get("tags", [])]
    if args.limit is not None:
        if args.limit < 1:
            raise ValueError("--limit must be positive")
        cases = cases[:args.limit]
    if not cases:
        raise ValueError("no evaluation cases selected")
    started = datetime.now(UTC).isoformat()
    invoker = FakeInvoker() if args.mode == "FAKE" else JavaHttpInvoker()
    judge = None
    if args.mode == "REAL":
        invoker.preflight()
        if not args.no_judge and os.getenv("DEVPILOT_EVAL_JUDGE_API_KEY"):
            judge = StructuredJudge()
    metadata = {
        "mode": args.mode,
        "synthetic_observations": args.mode == "FAKE",
        "dataset_name": Path(args.dataset).name,
        "dataset_version": 1,
        "dataset_hash": digest,
        "git_sha": _git_sha(),
        "generation_model": os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
        if args.mode == "REAL" else "FAKE",
        "judge_model": judge.model if judge else None,
        "judge_model_same_as_generation": (
            judge.model == os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
            if judge else False
        ),
        "workspace_id": getattr(invoker, "workspace_id", None),
        "project_id": getattr(invoker, "project_id", None),
        "repository_binding_id": getattr(invoker, "binding", None),
        "branch_name": getattr(invoker, "branch", None),
        "runtime_db_path": getattr(invoker, "runtime_db", None),
        "started_at": started,
    }
    print(json.dumps({
        "preflight": "OK", "mode": args.mode,
        "git_sha": metadata["git_sha"], "dataset_hash": digest,
        "java_reachable": args.mode == "REAL",
        "workspace_id": metadata["workspace_id"],
        "project_id": metadata["project_id"],
        "generation_model": metadata["generation_model"],
        "judge_model": metadata["judge_model"],
        "runtime_db_path": metadata["runtime_db_path"],
    }, ensure_ascii=False))
    try:
        rows, summary = EvaluationRunner(
            invoker, mode=args.mode, precondition_check=_precondition, judge=judge
        ).run(cases)
    finally:
        if hasattr(invoker, "close"):
            invoker.close()
    metadata["finished_at"] = datetime.now(UTC).isoformat()
    write_report(args.output_dir, rows, summary, metadata)
    print(json.dumps({
        "mode": args.mode, "cases": len(rows), "output_dir": args.output_dir,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
