import json

import pytest

from devpilot_agent_service.eval import cli
from devpilot_agent_service.eval.failure import classify
from devpilot_agent_service.eval.invoker import Invocation, JavaHttpInvoker
from devpilot_agent_service.eval.metrics import _rate, aggregate, case_metrics
from devpilot_agent_service.eval.report import RunArtifacts
from devpilot_agent_service.runtime.redaction import RuntimeRedactor


def _row(identifier: str, **metrics: object) -> dict:
    return {
        "id": identifier,
        "status": "SUCCEEDED",
        "trace": {},
        "metrics": {"answer_assertions": {}, **metrics},
    }


def _dataset(path) -> None:
    path.write_text(
        "".join(
            json.dumps(
                {
                    "schema_version": 1,
                    "id": f"case-{number}",
                    "category": "DIRECT",
                    "query": f"question {number}",
                    "expected_route": "DIRECT",
                }
            )
            + "\n"
            for number in range(3)
        ),
        encoding="utf-8",
    )


def test_rate_excludes_unknown_observations() -> None:
    assert _rate([True, None, False, True]) == 0.6667


def test_rate_returns_unknown_without_observations() -> None:
    assert _rate([None, None]) is None


def test_failed_case_does_not_score_missing_trace_as_false() -> None:
    metrics = case_metrics(
        {
            "expected_tools": ["knowledge.search"],
            "expected_sources": ["document.md"],
            "expected_delegation": False,
            "reliability": {"write_without_approval": False},
            "answer_assertions": {"must_include_all": ["answer"]},
        },
        "",
        {
            "tool_names": None,
            "rag_sources": None,
            "delegation_count": None,
            "write_without_approval": None,
        },
        "FAILED",
    )
    assert metrics["run_success"] is False
    for name in (
        "tool_exact_match",
        "source_recall_at_k",
        "delegation_match",
        "write_without_approval",
        "citation_present",
    ):
        assert metrics[name] is None
    assert metrics["answer_assertions"] == {}
    searched = case_metrics(
        {"expected_tools": ["knowledge.search"]},
        "",
        {"tool_names": ["knowledge.search"], "rag_sources": None},
        "FAILED",
    )
    assert searched["rag_empty_result"] is None


def test_aggregate_handles_unknown_cross_run_memory_success() -> None:
    summary = aggregate(
        [
            _row("one", cross_run_memory_success=True),
            _row("two", cross_run_memory_success=None),
            _row("three", cross_run_memory_success=False),
        ]
    )
    assert summary["cross_run_recall_success_rate"] == 0.5
    assert summary["cross_run_recall_success_rate_observed"] == 2


def test_aggregate_handles_unknown_expected_memory_recall_accuracy() -> None:
    summary = aggregate(
        [
            _row("one", expected_memory_recall_accuracy=None),
            _row("two", expected_memory_recall_accuracy=True),
        ]
    )
    assert summary["expected_memory_recall_accuracy"] == 1
    assert summary["expected_memory_recall_accuracy_observed"] == 1


def test_aggregate_preserves_unknown_route_tool_rag_context_and_memory() -> None:
    row = _row(
        "unknown",
        route_correct=None,
        tool_exact_match=None,
        source_recall_at_k=None,
        memory_write_correct=None,
        run_success=False,
    )
    row["trace"] = {
        "context_summary_used": None,
        "memory_write_attempted": True,
        "memory_written": None,
        "memory_recalled": None,
    }
    summary = aggregate([row])
    for name in (
        "route_correct",
        "tool_exact_match",
        "source_recall_at_k",
        "memory_write_correct",
        "context_summary_used_rate",
        "duplicate_memory_suppression_rate",
    ):
        assert summary[name] is None
        assert summary[name + "_observed"] == 0


def test_rate_denominator_is_reported_with_status_counts() -> None:
    failed = _row("model-error", route_correct=None, run_success=False)
    failed["status"] = "FAILED"
    failed["failure_kind"] = "MODEL_ERROR"
    summary = aggregate(
        [
            _row("success", route_correct=True, run_success=True),
            failed,
            _row("route-miss", route_correct=False, run_success=True),
        ]
    )
    assert summary["route_correct"] == 0.5
    assert summary["route_correct_observed"] == 2
    assert summary["run_success_observed"] == 3
    assert summary["cases_total"] == 3
    assert summary["cases_succeeded"] == 2
    assert summary["cases_failed"] == 1
    assert summary["cases_model_error"] == 1
    assert classify(failed)[0] == "MODEL_ERROR"


class SequenceInvoker:
    def __init__(self, output_dir, secret: str = "") -> None:
        self.output_dir = output_dir
        self.secret = secret
        self.invoked = 0

    def invoke(self, query: str, spec: dict) -> Invocation:
        del query
        if self.invoked == 0:
            assert (self.output_dir / "metadata.json").is_file()
            assert (self.output_dir / "cases.partial.jsonl").is_file()
        else:
            assert len((self.output_dir / "cases.partial.jsonl").read_text().splitlines()) == (
                self.invoked
            )
        self.invoked += 1
        if self.invoked == 2:
            return Invocation(spec["id"], "", "FAILED", {}, 10, "MODEL_ERROR")
        return Invocation(
            spec["id"], "ok " + self.secret, "SUCCEEDED", {"planner_route": "DIRECT"}, 10
        )


def test_model_error_is_a_case_result_and_report_is_written(tmp_path, monkeypatch) -> None:
    dataset = tmp_path / "dataset.jsonl"
    output_dir = tmp_path / "results"
    _dataset(dataset)
    monkeypatch.setenv("DEVPILOT_EVAL_BEARER_TOKEN", "test-secret-value")
    invoker = SequenceInvoker(output_dir, "test-secret-value")
    monkeypatch.setattr(cli, "FakeInvoker", lambda: invoker)

    args = ["run", "--mode", "FAKE", "--dataset", str(dataset), "--output-dir", str(output_dir)]
    assert cli.main(args) == 0

    rows = [json.loads(line) for line in (output_dir / "cases.jsonl").read_text().splitlines()]
    summary = json.loads((output_dir / "summary.json").read_text())
    assert len(rows) == 3
    assert rows[1]["status"] == "FAILED"
    assert rows[1]["failure_kind"] == "MODEL_ERROR"
    assert rows[1]["metrics"]["run_success"] is False
    assert rows[1]["metrics"]["route_correct"] is None
    assert summary["cases_succeeded"] == 2
    assert summary["cases_failed"] == 1
    assert summary["cases_model_error"] == 1
    assert summary["route_correct_observed"] == 2
    assert (output_dir / "report.md").is_file()
    assert (output_dir / "cases.partial.jsonl").read_bytes() == (
        output_dir / "cases.jsonl"
    ).read_bytes()
    assert "test-secret-value" not in (output_dir / "cases.partial.jsonl").read_text()


def test_java_failed_run_retains_model_error_kind() -> None:
    invoker = object.__new__(JavaHttpInvoker)
    invoker.base = "http://test.invalid"
    invoker.workspace_id = 1
    invoker.project_id = 2
    invoker.binding = None
    invoker.branch = None
    invoker._trace_store = type("Trace", (), {"get_trace": lambda self, _: {}})()
    invoker._request = lambda url, payload=None: {
        "runId": "run-1",
        "status": "FAILED",
        "failureKind": "MODEL_ERROR",
        "finalOutput": None,
    }

    result = invoker.invoke("question", {"id": "case-1"})
    assert result.status == "FAILED"
    assert result.failure_kind == "MODEL_ERROR"


def test_case_persistence_failure_is_explicit(tmp_path, monkeypatch) -> None:
    artifacts = RunArtifacts(tmp_path / "results", {"mode": "FAKE"}, RuntimeRedactor())

    def broken_dumps(*args: object, **kwargs: object) -> str:
        raise TypeError("unserializable observation")

    monkeypatch.setattr("devpilot_agent_service.eval.report.json.dumps", broken_dumps)
    with pytest.raises(RuntimeError, match="Failed to persist Eval case case-1"):
        artifacts.append_case({"id": "case-1"})
    assert (tmp_path / "results" / "metadata.json").is_file()


def test_aggregation_bug_preserves_partial_cases_and_metadata(tmp_path, monkeypatch) -> None:
    dataset = tmp_path / "dataset.jsonl"
    output_dir = tmp_path / "results"
    _dataset(dataset)
    monkeypatch.setattr(cli, "FakeInvoker", lambda: SequenceInvoker(output_dir))

    def broken_aggregate(rows: list[dict]) -> dict:
        assert len(rows) == 3
        raise TypeError("synthetic aggregation bug")

    monkeypatch.setattr(cli, "aggregate", broken_aggregate)
    with pytest.raises(TypeError, match="synthetic aggregation bug"):
        cli.main(
            ["run", "--mode", "FAKE", "--dataset", str(dataset), "--output-dir", str(output_dir)]
        )

    assert len((output_dir / "cases.partial.jsonl").read_text().splitlines()) == 3
    metadata = json.loads((output_dir / "metadata.json").read_text())
    error = json.loads((output_dir / "aggregation-error.json").read_text())
    assert metadata["dataset_hash"]
    assert metadata["started_at"] and metadata["finished_at"]
    assert metadata["eval_error"] == error
    assert error["phase"] == "aggregation"
    assert error["type"] == "TypeError"
    assert not (output_dir / "cases.jsonl").exists()

    monkeypatch.setattr(cli, "aggregate", aggregate)
    assert cli.main(["recover", "--output-dir", str(output_dir)]) == 0
    assert len((output_dir / "cases.jsonl").read_text().splitlines()) == 3
    recovered = json.loads((output_dir / "metadata.json").read_text())
    assert recovered["recovered_from"] == "cases.partial.jsonl"
    assert recovered["recovered_error"]["phase"] == "aggregation"
