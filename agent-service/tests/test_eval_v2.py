from collections import Counter
from datetime import datetime, timedelta

import pytest

from devpilot_agent_service.eval.dataset import load_dataset, validate_v2_splits
from devpilot_agent_service.eval.invoker import FakeInvoker, JavaHttpInvoker
from devpilot_agent_service.eval.metrics import (
    case_metrics,
    evidence_metrics,
    tool_argument_accuracy,
)
from devpilot_agent_service.eval.runner import EvaluationRunner
from devpilot_agent_service.eval.schema import EvalCase
from devpilot_agent_service.eval.seed import (
    FIXTURE_ROOT,
    TRANSITIONS,
    due_at,
    load_manifest,
    seed,
)

DATASETS = FIXTURE_ROOT.parent / "datasets"


class FixtureApi:
    workspace_id = 1
    project_id = 2
    project_path = "/api/v1/workspaces/1/projects/2"

    def __init__(self):
        self.tasks = []
        self.documents = []
        self.activities = 0
        self.activity_actions = []

    def request(self, path, payload=None, *, upload=None):
        if path == "/api/v1/auth/me":
            return {"id": 42}
        if path == self.project_path:
            return {"id": 2}
        if path.startswith(self.project_path + "/tasks?page="):
            return {"items": self.tasks, "total": len(self.tasks)}
        if path == self.project_path + "/tasks":
            task = {"id": len(self.tasks) + 1, "version": 0, "status": "BACKLOG", **payload}
            self.tasks.append(task)
            self.activities += 1
            self.activity_actions.append("create")
            return task
        if "/tasks/" in path:
            suffix = path.split("/tasks/", 1)[1].split("/")
            task = self.tasks[int(suffix[0]) - 1]
            if len(suffix) == 1:
                return {"task": task, "history": []}
            if payload["expectedVersion"] != task["version"]:
                raise RuntimeError("version conflict")
            if suffix[1] == "assign":
                task["assigneeUserId"] = payload["assigneeUserId"]
            elif suffix[1] == "unassign":
                task["assigneeUserId"] = None
            else:
                next_status = next(
                    target
                    for target, action in TRANSITIONS[task["status"]].items()
                    if action == suffix[1]
                )
                task["status"] = next_status
            task["version"] += 1
            self.activities += 1
            self.activity_actions.append(suffix[1])
            return task
        if path == self.project_path + "/knowledge/documents":
            if upload is None:
                return self.documents
            filename, content = upload
            from hashlib import sha256

            document = {
                "documentId": str(len(self.documents) + 1),
                "filename": filename,
                "sha256": sha256(content).hexdigest(),
                "status": "READY",
                "version": 1,
            }
            self.documents.append(document)
            return document
        if path == self.project_path + "/knowledge/search":
            return {
                "hits": [
                    {"sourceFile": "02-agent-runtime.md"},
                    {"sourceFile": "05-context-memory.md"},
                    {"sourceFile": "08-deployment-runbook.md"},
                ]
            }
        raise AssertionError(path)

    def put(self, path, payload):
        task = self.tasks[int(path.split("/tasks/")[1]) - 1]
        assert payload["expectedVersion"] == task["version"]
        task.update(payload)
        task["version"] += 1
        self.activities += 1
        return task


def test_manifest_and_idempotent_seed():
    manifest = load_manifest()
    assert len(manifest["tasks"]) == 28
    assert len(manifest["documents"]) == 12
    api = FixtureApi()
    fixed_now = datetime.now() - timedelta(days=1)
    first = seed(manifest, api, now=fixed_now, wait_seconds=0)
    activities = api.activities
    second = seed(manifest, api, now=fixed_now, wait_seconds=0)
    assert first["failed"] == second["failed"] == 0
    assert first["created"] == 40
    assert second["reused"] == 40
    assert len(api.tasks) == 28 and len(api.documents) == 12
    assert api.activities == activities
    assert {
        "create",
        "plan",
        "start",
        "submit-for-review",
        "request-changes",
        "complete",
        "reopen",
        "unassign",
        "assign",
    } <= set(api.activity_actions)
    assert Counter(item["status"] for item in api.tasks) == {
        "BACKLOG": 4,
        "TODO": 6,
        "IN_PROGRESS": 6,
        "IN_REVIEW": 4,
        "DONE": 5,
        "CANCELED": 3,
    }
    assert all(item["passed"] for item in first["knowledge_smoke"])
    assert first["overdue_ready_count"] == 6


def test_overdue_fixture_starts_with_a_legal_future_due_date():
    now = datetime(2026, 9, 22, 12, 0, 0)
    assert datetime.fromisoformat(due_at("OVERDUE", now)) > now


def test_v1_compatible_v2_valid_and_holdout_separate():
    old, _ = load_dataset(DATASETS / "devpilot_e2e_v1.jsonl")
    dev, _ = load_dataset(DATASETS / "devpilot_e2e_v2_dev.jsonl")
    holdout, _ = load_dataset(DATASETS / "devpilot_e2e_v2_holdout.jsonl")
    assert old and len(dev) == 60 and len(holdout) == 20
    assert not ({case.id for case in dev} & {case.id for case in holdout})
    assert not (
        {case.query for case in dev if case.query} & {case.query for case in holdout if case.query}
    )
    assert len({case.query for case in dev + holdout if case.query}) == 72
    pairs = Counter(
        case.spec.get("contrast_pair") for case in dev + holdout if case.spec.get("contrast_pair")
    )
    assert len(pairs) == 18 and set(pairs.values()) == {2}
    assert all(case.spec["split"] == "dev" for case in dev)
    assert all(case.spec["split"] == "holdout" for case in holdout)
    assert (
        validate_v2_splits(
            DATASETS / "devpilot_e2e_v2_dev.jsonl", DATASETS / "devpilot_e2e_v2_holdout.jsonl"
        )["contrast_pairs"]
        == 18
    )


def test_tool_argument_evidence_and_memory_negative_grading():
    assert (
        tool_argument_accuracy(
            {"knowledge.search": {"topK": {"min": 1, "max": 10}}},
            [{"name": "knowledge.search", "args": {"topK": 5}}],
        )
        == 1
    )
    assert (
        tool_argument_accuracy(
            {"knowledge.search": {"topK": {"min": 1, "max": 10}}},
            [{"name": "knowledge.search", "args": {"topK": 20}}],
        )
        == 0
    )
    recall, mrr, section = evidence_metrics(
        [{"source": "05-context-memory.md", "section": "Memory Scope Isolation"}],
        [
            {"source": "02-agent-runtime.md", "chunk_id": "a:1:0"},
            {"source": "05-context-memory.md", "chunk_id": "b:1:0"},
        ],
    )
    assert recall == 1 and mrr == 0.5 and section is None
    metrics = case_metrics({"expected_memory_write": False}, "", {"memory_written": 1}, "SUCCEEDED")
    assert metrics["memory_false_write"] is True


def test_v2_schema_rejects_unsafe_or_incomplete_ground_truth():
    base = {
        "schema_version": 2,
        "fixture_version": "devpilot-eval-v2",
        "id": "case",
        "category": "ONLY_RAG",
        "query": "query",
        "difficulty": "HARD",
    }
    with pytest.raises(ValueError, match="safe numeric"):
        EvalCase.parse(
            {**base, "expected_tool_args": {"knowledge.search": {"query": {"equals": 1}}}}
        )
    with pytest.raises(ValueError, match="expected_evidence"):
        EvalCase.parse({**base, "expected_evidence": [{"source": 123}]})
    with pytest.raises(ValueError, match="difficulty"):
        EvalCase.parse({**base, "difficulty": "unknown"})


def test_fake_runner_remains_explicitly_synthetic():
    cases, _ = load_dataset(DATASETS / "devpilot_e2e_v2_dev.jsonl")
    rows, _ = EvaluationRunner(FakeInvoker(), mode="FAKE").run(cases[:1])
    assert rows[0]["mode"] == "FAKE"
    assert rows[0]["answer"] == "FAKE EVALUATOR SELF-TEST"


def test_reject_scenario_uses_real_proposal_decision_endpoint_without_approval():
    invoker = object.__new__(JavaHttpInvoker)
    invoker.base = "http://test.invalid"
    invoker.workspace_id = 1
    invoker.project_id = 2
    invoker.binding = None
    invoker.branch = None
    invoker._trace_store = type("Trace", (), {"get_trace": lambda self, _: {}})()
    calls = []

    def request(url, payload=None):
        calls.append((url, payload))
        if url.endswith("/agent-runs"):
            return {"runId": "run-1", "status": "WAITING_APPROVAL"}
        if url.endswith("/proposals/pending"):
            return {
                "proposalId": "proposal-1",
                "status": "PENDING_APPROVAL",
                "toolName": "task.create",
                "resourceId": None,
            }
        if url.endswith("/decision"):
            assert payload == {"decision": "REJECT"}
            return {"status": "REJECTED", "resourceId": None}
        if url.endswith("/run-1"):
            return {"status": "SUCCEEDED", "finalOutput": "rejected"}
        raise AssertionError(url)

    invoker._request = request
    result = invoker.invoke("propose a task", {"reliability": {"scenario": "reject"}})
    assert result.status == "SUCCEEDED"
    assert result.trace["write_without_approval"] is False
    assert result.trace["unauthorized_tool_execution"] is False
    assert any(url.endswith("/decision") for url, _ in calls)
    assert not any(payload == {"decision": "APPROVE"} for _, payload in calls)


def test_unimplemented_reliability_transition_is_not_scored_as_pass():
    case = EvalCase.parse(
        {
            "schema_version": 2,
            "fixture_version": "devpilot-eval-v2",
            "id": "approval",
            "category": "ONLY_TOOL",
            "query": "propose",
            "difficulty": "HARD",
            "reliability": {"scenario": "approve_once"},
        }
    )
    rows, summary = EvaluationRunner(FakeInvoker(), mode="REAL").run([case])
    assert rows[0]["status"] == "SKIPPED_UNSUPPORTED"
    assert summary["hard_gates_pass"] is None


def test_no_fixture_document_contains_secrets():
    for path in (FIXTURE_ROOT / "knowledge").glob("*.md"):
        text = path.read_text(encoding="utf-8")
        assert "[EVAL-V2]" in text and "## " in text
        assert "Bearer " not in text and "sk-" not in text
