"""Fake evaluator self-test and real Java product-entry invokers."""

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from devpilot_agent_service.memory.store import MemoryStore


@dataclass(frozen=True, slots=True)
class Invocation:
    run_id: str
    answer: str
    status: str
    trace: dict
    latency_ms: int
    failure_kind: str | None = None


class FakeInvoker:
    """Synthetic observations for metric self-tests only; never claims Agent behavior."""

    def invoke(self, query: str, spec: dict) -> Invocation:
        del query
        tools = spec.get("expected_tools", [])
        return Invocation(
            "fake-" + spec["id"],
            "FAKE EVALUATOR SELF-TEST",
            "SUCCEEDED",
            {
                "planner_route": spec.get("expected_route"),
                "tool_names": tools,
                "rag_sources": spec.get("expected_sources", []),
                "delegation_count": int(bool(spec.get("expected_delegation"))),
                "model_call_count": 1,
                "tool_call_count": len(tools),
                "status": "SUCCEEDED",
            },
            0,
        )


class JavaHttpInvoker:
    TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "WAITING_APPROVAL"}
    supported_reliability = {"proposal", "reject", "cancel"}

    def __init__(self, environ=None) -> None:
        env = os.environ if environ is None else environ
        required = (
            "DEVPILOT_EVAL_JAVA_BASE_URL",
            "DEVPILOT_EVAL_BEARER_TOKEN",
            "DEVPILOT_EVAL_WORKSPACE_ID",
            "DEVPILOT_EVAL_PROJECT_ID",
        )
        missing = [key for key in required if not env.get(key)]
        if missing:
            raise ValueError("real eval environment missing: " + ", ".join(missing))
        if not env.get("DEEPSEEK_API_KEY"):
            raise ValueError("DEEPSEEK_API_KEY is required for real evaluation")
        self.base = env["DEVPILOT_EVAL_JAVA_BASE_URL"].rstrip("/")
        self.token = env["DEVPILOT_EVAL_BEARER_TOKEN"]
        self.workspace_id = int(env["DEVPILOT_EVAL_WORKSPACE_ID"])
        self.project_id = int(env["DEVPILOT_EVAL_PROJECT_ID"])
        self.binding = env.get("DEVPILOT_EVAL_REPOSITORY_BINDING_ID")
        self.branch = env.get("DEVPILOT_EVAL_BRANCH_NAME")
        self.runtime_db = env.get("AGENT_RUNTIME_DB_PATH")
        self.other_project_id = env.get("DEVPILOT_EVAL_OTHER_PROJECT_ID")
        self.other_workspace_id = env.get("DEVPILOT_EVAL_OTHER_WORKSPACE_ID")
        self.other_workspace_project_id = env.get("DEVPILOT_EVAL_OTHER_WORKSPACE_PROJECT_ID")
        if not self.runtime_db or not Path(self.runtime_db + ".memory").is_file():
            raise ValueError("AGENT_RUNTIME_DB_PATH must point to the running Python trace store")
        self._trace_store = MemoryStore(self.runtime_db + ".memory")

    @property
    def resource(self) -> str:
        return (
            f"{self.base}/api/v1/workspaces/{self.workspace_id}/"
            f"projects/{self.project_id}/agent-runs"
        )

    def _request(self, url: str, payload: dict | None = None) -> dict:
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(
            url,
            data=data,
            headers={
                "Authorization": "Bearer " + self.token,
                "Content-Type": "application/json",
            },
            method="POST" if data is not None else "GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                body = json.load(response)
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            # Never print URL, headers, token, Java response body, or provider payload.
            raise RuntimeError(f"Java AgentRun request failed: {type(error).__name__}") from None
        if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
            raise RuntimeError("Java AgentRun returned an invalid envelope")
        return body["data"]

    def preflight(self) -> None:
        self._request(self.resource + "?page=0&size=1")

    def invoke(self, query: str, spec: dict) -> Invocation:
        scope = spec.get("scope")
        resource = self.resource
        if scope == "OTHER_PROJECT":
            if not self.other_project_id:
                raise ValueError("OTHER_PROJECT requires DEVPILOT_EVAL_OTHER_PROJECT_ID")
            resource = (
                f"{self.base}/api/v1/workspaces/{self.workspace_id}/"
                f"projects/{int(self.other_project_id)}/agent-runs"
            )
        elif scope == "OTHER_WORKSPACE":
            if not self.other_workspace_id or not self.other_workspace_project_id:
                raise ValueError("OTHER_WORKSPACE requires explicit other workspace/project IDs")
            resource = (
                f"{self.base}/api/v1/workspaces/{int(self.other_workspace_id)}/"
                f"projects/{int(self.other_workspace_project_id)}/agent-runs"
            )
        payload = {"input": query}
        if self.binding:
            payload["repositoryBindingId"] = int(self.binding)
        if self.branch:
            payload["branchName"] = self.branch
        started = time.monotonic()
        run = self._request(resource, payload)
        run_id = run.get("runId")
        if not isinstance(run_id, str) or not run_id:
            raise RuntimeError("Java AgentRun did not return a run ID")
        deadline = started + 180
        while run.get("status") not in self.TERMINAL:
            if time.monotonic() > deadline:
                raise RuntimeError("Java AgentRun terminal wait timed out")
            time.sleep(0.5)
            run = self._request(resource + "/" + run_id)
        control = {}
        scenario = spec.get("reliability", {}).get("scenario")
        if scenario in self.supported_reliability:
            if run["status"] == "WAITING_APPROVAL":
                proposals = resource + "/" + run_id + "/proposals"
                pending = self._request(proposals + "/pending")
                valid = (
                    pending.get("status") == "PENDING_APPROVAL"
                    and pending.get("toolName") == "task.create"
                )
                control["write_without_approval"] = bool(pending.get("resourceId"))
                control["unauthorized_tool_execution"] = not valid
                proposal_id = pending["proposalId"]
                if scenario == "reject":
                    decision = self._request(
                        proposals + "/" + proposal_id + "/decision", {"decision": "REJECT"}
                    )
                    control["write_without_approval"] = bool(decision.get("resourceId"))
                    control["unauthorized_tool_execution"] = (
                        not valid or decision.get("status") != "REJECTED"
                    )
                elif scenario == "cancel":
                    self._request(resource + "/" + run_id + "/cancel", {})
                if scenario in {"reject", "cancel"}:
                    while run.get("status") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                        if time.monotonic() > deadline:
                            raise RuntimeError("Java reliability scenario wait timed out")
                        time.sleep(0.5)
                        run = self._request(resource + "/" + run_id)
        trace = self._trace_store.get_trace(run_id)
        return Invocation(
            run_id,
            str(run.get("finalOutput") or ""),
            str(run["status"]),
            {**(trace or {}), **control},
            int((time.monotonic() - started) * 1000),
            run.get("failureKind") if isinstance(run.get("failureKind"), str) else None,
        )

    def close(self) -> None:
        self._trace_store.close()
