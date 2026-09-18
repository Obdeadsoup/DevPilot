"""Independent LangGraph invocation with a bounded structured handoff."""

import json
from collections.abc import Callable
from dataclasses import dataclass
from hashlib import sha256

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.agents.registry import project_analyst_registry
from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.graph.builder import build_agent_graph
from devpilot_agent_service.harness.prompts import ANALYST_PROMPT
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.registry import ToolRegistry


@dataclass(frozen=True, slots=True)
class AnalystResult:
    handoff: dict
    model_call_count: int
    tool_call_count: int


class ProjectAnalyst:
    def __init__(
        self,
        model_factory: Callable[[], Model],
        registry: ToolRegistry,
        *,
        context_manager: ContextManager,
        max_steps: int = 6,
        max_tool_calls: int = 8,
        redactor: RuntimeRedactor | None = None,
    ) -> None:
        self._registry = project_analyst_registry(registry)
        self._model_factory = model_factory
        self._context_manager = context_manager
        self._max_steps = max_steps
        self._max_tool_calls = max_tool_calls
        self._redactor = redactor or RuntimeRedactor()

    def invoke(self, task: str, *, run_context: RunContext, delegation_id: str) -> AnalystResult:
        if not isinstance(task, str) or not 1 <= len(task.strip()) <= 2000:
            raise ValueError("analyst task must contain 1 to 2000 characters")
        if not isinstance(run_context, RunContext):
            raise TypeError("analyst requires authoritative RunContext")
        # Never accept a main transcript. Every handoff starts a fresh independent state/model.
        namespace = "analyst:" + sha256(delegation_id.encode()).hexdigest()[:16]
        graph = build_agent_graph(
            self._model_factory(),
            project_analyst_registry(self._registry),
            context_manager=self._context_manager,
            max_steps=self._max_steps,
            max_tool_calls=self._max_tool_calls,
            tool_call_namespace=namespace,
        )
        state = graph.invoke(
            {
                "messages": [
                    SystemMessage(content=ANALYST_PROMPT),
                    HumanMessage(content=str(self._redactor.redact(task))),
                ],
                "run_id": run_context.run_id,
                "request_id": run_context.request_id,
                "tool_call_count": 0,
                "model_call_count": 0,
                "final_answer": None,
                "stop_reason": None,
            },
            {"recursion_limit": 2 * self._max_steps + 3},
        )
        sources = self._observed_sources(state["messages"])
        handoff = self._bounded_handoff(state["final_answer"], sources)
        return AnalystResult(handoff, state["model_call_count"], state["tool_call_count"])

    def _observed_sources(self, messages) -> set[str]:
        sources: set[str] = set()

        def visit(value):
            if isinstance(value, dict):
                source = value.get("sourceFile")
                if isinstance(source, str) and 1 <= len(source) <= 256:
                    sources.add(str(self._redactor.redact(source))[:256])
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        for message in messages:
            if isinstance(message, ToolMessage) and message.name == "knowledge.search":
                try:
                    visit(json.loads(message.content))
                except (ValueError, RecursionError):
                    pass
        return sources

    def _bounded_handoff(self, answer: str, observed_sources: set[str]) -> dict:
        fallback = {
            "summary": "Project Analyst did not return a valid structured analysis.",
            "key_findings": [],
            "sources": sorted(observed_sources)[:10],
            "warnings": ["invalid analyst response; no transcript returned"],
        }
        fallback = self._cap_handoff(fallback)
        if len(answer) > 100_000:
            return fallback
        try:
            data = json.loads(answer)
            if not isinstance(data, dict) or set(data) != {
                "summary",
                "key_findings",
                "sources",
                "warnings",
            }:
                return fallback
            if not isinstance(data["summary"], str) or not data["summary"].strip():
                return fallback
            if any(
                not isinstance(data[key], list) or any(not isinstance(x, str) for x in data[key])
                for key in ("key_findings", "sources", "warnings")
            ):
                return fallback
        except (ValueError, RecursionError):
            return fallback
        safe = self._redactor.redact(data)
        warnings = [value[:240] for value in safe["warnings"][:5]]
        if set(safe["sources"]) - observed_sources:
            warnings = (warnings + ["unobserved source identifiers removed"])[-5:]
        # Field limits plus a serialized cap bound escaping-heavy output too, without transcript.
        handoff = {
            "summary": safe["summary"][:2000],
            "key_findings": [value[:400] for value in safe["key_findings"][:5]],
            "sources": sorted(observed_sources)[:10],
            "warnings": warnings,
        }
        return self._cap_handoff(handoff)

    @staticmethod
    def _cap_handoff(handoff: dict) -> dict:
        while len(json.dumps(handoff, ensure_ascii=False)) > 8000:
            if len(handoff["summary"]) > 500:
                handoff["summary"] = handoff["summary"][: len(handoff["summary"]) // 2]
            elif handoff["key_findings"]:
                handoff["key_findings"].pop()
            elif handoff["warnings"]:
                handoff["warnings"].pop()
            elif handoff["sources"]:
                handoff["sources"].pop()
            else:
                handoff["summary"] = handoff["summary"][: max(1, len(handoff["summary"]) // 2)]
        return handoff
