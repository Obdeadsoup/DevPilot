"""Idempotent V2 fixture seeding through DevPilot's authenticated HTTP API."""

import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from collections import Counter, deque
from datetime import UTC, datetime, timedelta
from pathlib import Path

FIXTURE_ROOT = Path(__file__).resolve().parents[3] / "evals" / "fixtures"
TRANSITIONS = {
    "BACKLOG": {"TODO": "plan", "CANCELED": "cancel"},
    "TODO": {"BACKLOG": "return-to-backlog", "IN_PROGRESS": "start", "CANCELED": "cancel"},
    "IN_PROGRESS": {"IN_REVIEW": "submit-for-review", "CANCELED": "cancel"},
    "IN_REVIEW": {"IN_PROGRESS": "request-changes", "DONE": "complete", "CANCELED": "cancel"},
    "DONE": {"TODO": "reopen"},
    "CANCELED": {"TODO": "reopen"},
}


def load_manifest(path: Path = FIXTURE_ROOT / "manifest_v2.json") -> dict:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("fixture_version") != "devpilot-eval-v2":
        raise ValueError("unsupported fixture version")
    tasks = manifest.get("tasks", [])
    documents = manifest.get("documents", [])
    if len(tasks) < 24 or len(documents) < 10:
        raise ValueError("fixture needs at least 24 tasks and 10 documents")
    ids = [task["fixture_id"] for task in tasks]
    titles = [task["title"] for task in tasks]
    files = [doc["source"] for doc in documents]
    if len(set(ids)) != len(ids) or len(set(titles)) != len(titles):
        raise ValueError("duplicate task fixture")
    if len(set(files)) != len(files):
        raise ValueError("duplicate document fixture")
    if sum(task["status"] not in {"DONE", "CANCELED"} for task in tasks) > 20:
        raise ValueError("open task fixture exceeds task.list_open's 20-result limit")
    for task in tasks:
        if (
            task["status"] not in TRANSITIONS
            or task["priority"] not in {"LOW", "MEDIUM", "HIGH", "URGENT"}
            or task["due"] not in {"OVERDUE", "SOON", "FUTURE", "NONE"}
        ):
            raise ValueError("invalid task fixture")
        if not task["title"].startswith(f"[EVAL-V2][{task['fixture_id']}]"):
            raise ValueError("task title lacks stable marker")
        if len(task.get("description", "")) < 60:
            raise ValueError("task description lacks evaluation context")
        activity_status = "BACKLOG"
        for action in task.get("activity_sequence", []):
            if action in {"assign", "unassign"}:
                continue
            next_status = next(
                (
                    target
                    for target, allowed in TRANSITIONS[activity_status].items()
                    if allowed == action
                ),
                None,
            )
            if next_status is None:
                raise ValueError("invalid activity sequence")
            activity_status = next_status
    for doc in documents:
        target = FIXTURE_ROOT / "knowledge" / doc["source"]
        if not target.is_file() or "[EVAL-V2]" not in target.read_text(encoding="utf-8"):
            raise ValueError(f"missing or unmarked knowledge fixture: {doc['source']}")
        for section in doc.get("sections", []):
            if f"## {section}" not in target.read_text(encoding="utf-8"):
                raise ValueError(f"missing evidence section: {doc['source']} / {section}")
    return manifest


class DevPilotApi:
    def __init__(self, environ=None):
        env = os.environ if environ is None else environ
        names = (
            "DEVPILOT_EVAL_JAVA_BASE_URL",
            "DEVPILOT_EVAL_BEARER_TOKEN",
            "DEVPILOT_EVAL_WORKSPACE_ID",
            "DEVPILOT_EVAL_PROJECT_ID",
        )
        missing = [name for name in names if not env.get(name)]
        if missing:
            raise ValueError("seed environment missing: " + ", ".join(missing))
        self.base = env[names[0]].rstrip("/")
        self.token = env[names[1]]
        self.workspace_id = int(env[names[2]])
        self.project_id = int(env[names[3]])
        if self.workspace_id < 1 or self.project_id < 1:
            raise ValueError("workspace and project IDs must be positive")
        self.project_path = f"/api/v1/workspaces/{self.workspace_id}/projects/{self.project_id}"

    def request(
        self,
        path: str,
        payload=None,
        *,
        upload: tuple[str, bytes] | None = None,
        method: str | None = None,
    ):
        headers = {"Authorization": "Bearer " + self.token}
        data = None
        method = "GET"
        if upload is not None:
            filename, content = upload
            boundary = "devpilot-eval-v2-boundary"
            data = (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n'
            ).encode()
            data += content + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
            method = "POST"
        elif payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
            method = "POST"
        request = urllib.request.Request(
            self.base + path,
            data=data,
            headers=headers,
            method=method or ("POST" if data is not None else "GET"),
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                envelope = json.load(response)
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            # Do not serialize URLs, response bodies or headers: they may contain credentials.
            raise RuntimeError(f"DevPilot API request failed: {type(error).__name__}") from None
        if not isinstance(envelope, dict) or envelope.get("code") != "COMMON_0000":
            raise RuntimeError("DevPilot API returned an unsuccessful envelope")
        return envelope.get("data")

    def put(self, path: str, payload: dict):
        return self.request(path, payload, method="PUT")


def due_at(kind: str, now: datetime) -> str | None:
    if kind == "OVERDUE":
        # The product API rejects past dueAt. Create a legal near-future date, then
        # wait for it to become overdue before declaring the fixture ready.
        return (now + timedelta(seconds=60)).replace(microsecond=0).isoformat()
    days = {"SOON": 1, "FUTURE": 30, "NONE": None}[kind]
    return (
        (now + timedelta(days=days)).replace(hour=12, minute=0, second=0, microsecond=0).isoformat()
        if days is not None
        else None
    )


def _server_now() -> datetime:
    offset = int(os.getenv("DEVPILOT_EVAL_SERVER_UTC_OFFSET_HOURS", "0"))
    if not -12 <= offset <= 14:
        raise ValueError("server UTC offset must be between -12 and 14")
    return (datetime.now(UTC) + timedelta(hours=offset)).replace(tzinfo=None)


def _due_matches(kind: str, observed: str | None, desired: str | None, current: datetime) -> bool:
    if kind != "OVERDUE":
        return observed == desired
    if not observed:
        return False
    deadline = datetime.fromisoformat(observed)
    return deadline <= current + timedelta(seconds=60)


def transition_path(start: str, target: str) -> list[str]:
    queue = deque([(start, [])])
    seen = {start}
    while queue:
        current, path = queue.popleft()
        if current == target:
            return path
        for next_status, action in TRANSITIONS[current].items():
            if next_status not in seen:
                seen.add(next_status)
                queue.append((next_status, path + [action]))
    raise ValueError(f"no legal task transition from {start} to {target}")


def _paged_tasks(api):
    items = []
    page = 1
    while True:
        data = api.request(api.project_path + f"/tasks?page={page}&size=100")
        items.extend(data["items"])
        if len(items) >= data["total"]:
            return items
        page += 1


def seed(manifest: dict, api, *, now: datetime | None = None, wait_seconds: int = 300) -> dict:
    fixed_now = now
    now = now or _server_now()
    report = {
        "fixture_version": manifest["fixture_version"],
        "seeded_at": now.isoformat(),
        "workspace_id": api.workspace_id,
        "project_id": api.project_id,
        "created": 0,
        "reused": 0,
        "updated": 0,
        "failed": 0,
        "resources": [],
        "knowledge_smoke": [],
    }
    actor_id = api.request("/api/v1/auth/me")["id"]
    api.request(api.project_path)
    task_path = api.project_path + "/tasks"
    existing = _paged_tasks(api)
    by_title = {}
    for item in existing:
        by_title.setdefault(item["title"], []).append(item)
    for fixture in manifest["tasks"]:
        key = fixture["fixture_id"]
        title = fixture["title"]
        try:
            matches = by_title.get(title, [])
            if len(matches) > 1:
                raise ValueError("duplicate task title in target project")
            current = fixed_now or _server_now()
            desired_due = due_at(fixture["due"], current)
            if matches:
                listed = matches[0]
                task = api.request(task_path + "/" + str(listed["id"]))["task"]
                action = "reused"
            else:
                task = api.request(
                    task_path,
                    {
                        "title": title,
                        "description": fixture["description"],
                        "priority": fixture["priority"],
                        "assigneeUserId": actor_id if fixture["assigned"] else None,
                        "dueAt": desired_due,
                    },
                )
                action = "created"
                by_title[title] = [task]
            resource = task_path + "/" + str(task["id"])
            if (
                task["priority"] != fixture["priority"]
                or not _due_matches(fixture["due"], task.get("dueAt"), desired_due, current)
                or (
                    task.get("description") is not None
                    and task["description"] != fixture["description"]
                )
            ):
                # PUT is the product profile API; urllib's default POST is overridden below.
                task = _update_task(api, resource, fixture, desired_due, task["version"])
                action = "updated" if action == "reused" else action
            for step in fixture.get("activity_sequence", []) if action == "created" else []:
                task = api.request(
                    resource + "/" + step,
                    {"expectedVersion": task["version"], "reason": "[EVAL-V2] fixture"},
                )
                action = "updated" if action == "reused" else action
            want_assignee = actor_id if fixture["assigned"] else None
            if task.get("assigneeUserId") != want_assignee:
                endpoint = "assign" if want_assignee else "unassign"
                payload = {"expectedVersion": task["version"]}
                if want_assignee:
                    payload["assigneeUserId"] = want_assignee
                task = api.request(resource + "/" + endpoint, payload)
                action = "updated" if action == "reused" else action
            for step in transition_path(task["status"], fixture["status"]):
                task = api.request(
                    resource + "/" + step,
                    {"expectedVersion": task["version"], "reason": "[EVAL-V2] fixture"},
                )
                action = "updated" if action == "reused" else action
            report[action] += 1
            report["resources"].append(
                {
                    "type": "task",
                    "fixture_id": key,
                    "id": task["id"],
                    "action": action,
                    "status": task["status"],
                }
            )
        except (RuntimeError, ValueError, KeyError) as error:
            report["failed"] += 1
            report["resources"].append(
                {"type": "task", "fixture_id": key, "action": "failed", "reason": str(error)}
            )
    document_path = api.project_path + "/knowledge/documents"
    try:
        documents = api.request(document_path)
    except RuntimeError as error:
        report["failed"] += len(manifest["documents"])
        report["resources"].append(
            {"type": "document_list", "action": "failed", "reason": str(error)}
        )
        report.update(
            {
                "document_count": 0,
                "ready_count": 0,
                "failed_count": 0,
                "pending_ingestion": [],
                "task_count": len(
                    [
                        r
                        for r in report["resources"]
                        if r["type"] == "task" and r["action"] != "failed"
                    ]
                ),
                "task_status_distribution": dict(
                    Counter(
                        r["status"]
                        for r in report["resources"]
                        if r["type"] == "task" and "status" in r
                    )
                ),
            }
        )
        return report
    by_name = {}
    for item in documents:
        by_name.setdefault(item["filename"], []).append(item)
    for fixture in manifest["documents"]:
        filename = fixture["source"]
        content = (FIXTURE_ROOT / "knowledge" / filename).read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        try:
            matches = by_name.get(filename, [])
            if len(matches) > 1:
                raise ValueError("duplicate document filename in target project")
            if matches:
                document = matches[0]
                if document["sha256"] != digest:
                    raise ValueError("existing document content differs; choose a clean project")
                action = "reused"
                if document["status"] == "FAILED":
                    document = api.request(
                        document_path + "/" + document["documentId"] + "/retry",
                        {"expectedVersion": document["version"]},
                    )
                    action = "updated"
            else:
                document = api.request(document_path, upload=(filename, content))
                action = "created"
            report[action] += 1
            report["resources"].append(
                {
                    "type": "document",
                    "source": filename,
                    "id": document["documentId"],
                    "action": action,
                }
            )
        except (RuntimeError, ValueError, KeyError) as error:
            report["failed"] += 1
            report["resources"].append(
                {"type": "document", "source": filename, "action": "failed", "reason": str(error)}
            )
    expected_files = {item["source"] for item in manifest["documents"]}
    deadline = time.monotonic() + wait_seconds
    while True:
        current = {
            item["filename"]: item
            for item in api.request(document_path)
            if item["filename"] in expected_files
        }
        pending = [
            name
            for name in expected_files
            if current.get(name, {}).get("status") not in {"READY", "FAILED"}
        ]
        if not pending or time.monotonic() >= deadline:
            break
        time.sleep(2)
    report["document_count"] = len(current)
    report["ready_count"] = sum(item["status"] == "READY" for item in current.values())
    report["failed_count"] = sum(item["status"] == "FAILED" for item in current.values())
    report["ingestion_failures"] = [
        {"source": name, "failure_code": item.get("failureCode")}
        for name, item in current.items()
        if item["status"] == "FAILED"
    ]
    report["pending_ingestion"] = pending
    if pending or report["failed_count"] or len(current) != len(expected_files):
        report["failed"] += (
            len(pending) + report["failed_count"] + len(expected_files - current.keys())
        )
    if report["failed"] == 0:
        for fixture in manifest.get("smoke_queries", []):
            query = fixture["query"]
            result = api.request(
                api.project_path + "/knowledge/search", {"query": query, "topK": 5}
            )
            sources = [hit["sourceFile"] for hit in result["hits"]]
            passed = any(source in sources for source in fixture["expected_sources"])
            report["knowledge_smoke"].append(
                {"query": query, "topK_sources": sources, "passed": passed}
            )
            report["failed"] += int(not passed)
    report["task_count"] = sum(
        r["type"] == "task" and r["action"] != "failed" for r in report["resources"]
    )
    report["task_status_distribution"] = dict(
        Counter(r["status"] for r in report["resources"] if r["type"] == "task" and "status" in r)
    )
    fixture_titles = {task["title"] for task in manifest["tasks"]}
    all_tasks = _paged_tasks(api)
    actual_by_title = {}
    for task in all_tasks:
        actual_by_title.setdefault(task["title"], []).append(task)
    report["task_verification_failures"] = [
        fixture["fixture_id"]
        for fixture in manifest["tasks"]
        if len(actual_by_title.get(fixture["title"], [])) != 1
        or actual_by_title[fixture["title"]][0]["status"] != fixture["status"]
        or actual_by_title[fixture["title"]][0]["priority"] != fixture["priority"]
    ]
    report["failed"] += len(report["task_verification_failures"])
    overdue_titles = {task["title"] for task in manifest["tasks"] if task["due"] == "OVERDUE"}
    overdue_deadlines = [
        datetime.fromisoformat(task["dueAt"])
        for task in all_tasks
        if task["title"] in overdue_titles and task.get("dueAt")
    ]
    if fixed_now is None and overdue_deadlines:
        remaining = (max(overdue_deadlines) - _server_now()).total_seconds() + 1
        if remaining > 0:
            time.sleep(remaining)
    report["overdue_ready_count"] = sum(
        task["title"] in overdue_titles
        and task.get("dueAt") is not None
        and datetime.fromisoformat(task["dueAt"]) < _server_now()
        for task in all_tasks
    )
    report["failed"] += int(report["overdue_ready_count"] != len(overdue_titles))
    report["extraneous_open_task_count"] = sum(
        task["title"] not in fixture_titles and task["status"] not in {"DONE", "CANCELED"}
        for task in all_tasks
    )
    report["failed"] += int(report["extraneous_open_task_count"] > 0)
    return report


def _update_task(api, path, fixture, due, version):
    payload = {
        "title": fixture["title"],
        "description": fixture["description"],
        "priority": fixture["priority"],
        "dueAt": due,
        "expectedVersion": version,
    }
    if not hasattr(api, "put"):
        raise RuntimeError("API client does not support task profile update")
    return api.put(path, payload)


def write_seed_report(report: dict, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "seed-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
