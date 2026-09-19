import json

import pytest

from devpilot_agent_service.eval.dataset import load_dataset
from devpilot_agent_service.eval.invoker import FakeInvoker, JavaHttpInvoker
from devpilot_agent_service.eval.metrics import case_metrics
from devpilot_agent_service.eval.report import write_report
from devpilot_agent_service.eval.runner import EvaluationRunner


def test_dataset_schema_and_fake_runner_are_explicitly_not_real(tmp_path):
    dataset = tmp_path / "cases.jsonl"
    dataset.write_text(json.dumps({
        "schema_version": 1, "id": "case-1", "category": "ONLY_TOOL",
        "query": "open tasks", "expected_route": "ONLY_TOOL",
        "expected_tools": ["task.list_open"],
        "forbidden_tools": ["task.create"],
    }) + "\n", encoding="utf-8")
    cases, digest = load_dataset(dataset)
    rows, summary = EvaluationRunner(FakeInvoker(), mode="FAKE").run(cases)
    assert rows[0]["mode"] == "FAKE"
    assert rows[0]["metrics"]["route_correct"]
    assert summary["route_correct"] == 1
    write_report(tmp_path / "report", rows, summary, {
        "mode": "FAKE", "dataset_hash": digest,
    })
    assert json.loads((tmp_path / "report" / "metadata.json").read_text())["mode"] == "FAKE"
    assert "Mode: **FAKE**" in (tmp_path / "report" / "report.md").read_text()


def test_invalid_dataset_fails_before_any_invocation(tmp_path):
    dataset = tmp_path / "bad.jsonl"
    dataset.write_text('{"schema_version":2,"id":"bad"}\n', encoding="utf-8")
    with pytest.raises(ValueError, match="invalid dataset line"):
        load_dataset(dataset)


def test_metric_computation_catches_forbidden_tool_and_missing_source():
    metrics = case_metrics(
        {
            "expected_route": "ONLY_RAG", "expected_tools": ["knowledge.search"],
            "forbidden_tools": ["task.create"], "expected_sources": ["architecture.md"],
            "answer_assertions": {"must_include_any": ["Outbox"]},
        },
        "No source", {
            "planner_route": "ONLY_TOOL",
            "tool_names": ["task.create"],
            "rag_sources": [],
        },
        "SUCCEEDED",
    )
    assert metrics["route_correct"] is False
    assert metrics["forbidden_tool_violation"] is True
    assert metrics["source_recall_at_k"] == 0
    assert metrics["answer_assertions"]["include_any"] is False


def test_real_invoker_requires_java_and_provider_configuration():
    with pytest.raises(ValueError, match="real eval environment missing"):
        JavaHttpInvoker({})
