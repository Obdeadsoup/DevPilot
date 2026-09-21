"""Query Planner workflow API with durable LangGraph continuation."""

import json
import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.types import Command

from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.graph.workflow import BUSINESS_TOOLS, build_workflow_graph
from devpilot_agent_service.harness.prompts import UNTRUSTED_DATA_GUARD
from devpilot_agent_service.harness.runtime import HarnessConfig, HarnessRuntime
from devpilot_agent_service.memory.store import MemoryStore
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.planner import QueryPlanner
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import ApprovalRequired, ResumeRejected, UnknownToolError
from devpilot_agent_service.runtime.events import RuntimeEvent
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.base import ToolProposal, ToolProposalResolution
from devpilot_agent_service.tools.registry import ToolRegistry

WORKFLOW_PROMPT = (
    "You are the DevPilot read-oriented assistant. "
    + UNTRUSTED_DATA_GUARD
    + "The explicit Query Planner selects the route; use only this node's supplied capabilities. "
    "plan.update, if available, manages todos and never changes the route. "
    "Business writes require the explicit proposal and approval workflow."
)


@dataclass(frozen=True, slots=True)
class WorkflowResult:
    final_answer: str
    stop_reason: str
    planner_decision: dict
    planner_elapsed_ms: int
    planner_call_count: int
    plan: list
    model_call_count: int
    tool_call_count: int
    delegation_count: int
    delegation_trace: list
    context_summary: str | None
    used_business_tool: bool
    used_rag: bool
    write_path_required: bool
    tool_names: tuple[str, ...] = ()
    rag_sources: tuple[str, ...] = ()
    memory_recalled: int = 0
    context_omitted_messages: int = 0
    truncated_tool_result_count: int = 0

    def safe_trace(self) -> dict:
        """Public control-flow only: no rewritten query, model reasoning or credentials."""
        return {
            "planner_route": self.planner_decision["route"],
            "planner_confidence": self.planner_decision["confidence"],
            "planner_reason_code": self.planner_decision["reason_code"],
            "planner_elapsed_ms": self.planner_elapsed_ms,
            "planner_call_count": self.planner_call_count,
            "used_business_tool": self.used_business_tool,
            "used_rag": self.used_rag,
            "delegation_count": self.delegation_count,
            "model_call_count": self.model_call_count,
            "tool_call_count": self.tool_call_count,
            "subagent_model_call_count": sum(
                record["model_call_count"] for record in self.delegation_trace
            ),
            "subagent_tool_call_count": sum(
                record["tool_call_count"] for record in self.delegation_trace
            ),
            "stop_reason": self.stop_reason,
            "final_status": "SUCCEEDED",
            "write_path_required": self.write_path_required,
            "tool_names": list(self.tool_names),
            "rag_sources": list(self.rag_sources),
            "context_summary_used": bool(self.context_summary),
            "memory_recalled": self.memory_recalled,
            "context_omitted_messages": self.context_omitted_messages,
            "truncated_tool_result_count": self.truncated_tool_result_count,
        }


class WorkflowRuntime(HarnessRuntime):
    def __init__(
        self,
        model: Model,
        registry: ToolRegistry,
        analyst_model_factory: Callable[[], Model],
        *,
        planner_model: Model | None = None,
        planner_timeout_seconds: float = 5.0,
        config: HarnessConfig | None = None,
        redactor: RuntimeRedactor | None = None,
        checkpointer=None,
        memory_store: MemoryStore | None = None,
    ) -> None:
        super().__init__(
            model, registry, analyst_model_factory,
            config=config, redactor=redactor, build_main_graph=False,
        )
        try:
            self.registry.register(registry.get("task.create"))
        except UnknownToolError:
            pass
        self._model = model
        self._context = ContextManager(self.config.context_budget, redactor=self._redactor)
        self._planner = QueryPlanner(
            planner_model or model, self._context, timeout_seconds=planner_timeout_seconds
        )
        self._checkpointer = checkpointer
        self._memory_store = memory_store

    def invoke(
        self,
        user_input: str,
        *,
        run_context: RunContext | None = None,
        history: Sequence[BaseMessage] = (),
        on_event: Callable[[RuntimeEvent], None] | None = None,
        safe_point: Callable[[], None] | None = None,
    ) -> WorkflowResult:
        if not isinstance(user_input, str) or not user_input.strip():
            raise ValueError("workflow query must not be blank")
        if run_context is not None and not isinstance(run_context, RunContext):
            raise TypeError("workflow requires authoritative RunContext")
        if any(
            not isinstance(message, BaseMessage) or isinstance(message, SystemMessage)
            for message in history
        ):
            raise ValueError("history must contain conversation, not system policy")
        state = self._build_graph(on_event, safe_point, run_context).invoke(
            {
                "messages": [
                    SystemMessage(content=WORKFLOW_PROMPT),
                    *history,
                    HumanMessage(content=user_input),
                ],
                "run_id": run_context.run_id if run_context else None,
                "request_id": run_context.request_id if run_context else None,
                "model_call_count": 0,
                "tool_call_count": 0,
                "plan": [],
                "delegation_count": 0,
                "delegation_trace": [],
                "context_summary": None,
                "final_answer": None,
                "stop_reason": None,
                "write_path_required": False,
                "pending_proposal": None,
                "memory_context": "",
                "memory_recall_ids": [],
                "summary_seen": [],
            },
            self._config(run_context),
        )
        return self._result(state)

    def resume(
        self,
        run_context: RunContext,
        *,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        safe_point: Callable[[], None] | None = None,
    ) -> WorkflowResult:
        if self._checkpointer is None:
            raise ResumeRejected("CHECKPOINT_NOT_CONFIGURED")
        graph = self._build_graph(on_event, safe_point, run_context)
        snapshot = graph.get_state(self._config(run_context))
        if not snapshot.values or not snapshot.next:
            raise ResumeRejected("CHECKPOINT_NOT_RESUMABLE")
        if (snapshot.values.get("run_id"), snapshot.values.get("request_id")) != (
            run_context.run_id, run_context.request_id
        ):
            raise ResumeRejected("CHECKPOINT_IDENTITY_MISMATCH")
        return self._result(graph.invoke(None, self._config(run_context)))

    def checkpoint_state(self, run_context: RunContext):
        if self._checkpointer is None:
            return None
        return self._build_graph(None, None, run_context).get_state(self._config(run_context))

    def resume_approval(
        self,
        run_context: RunContext,
        resolution: ToolProposalResolution,
        *,
        on_event: Callable[[RuntimeEvent], None] | None = None,
        safe_point: Callable[[], None] | None = None,
    ) -> WorkflowResult:
        if self._checkpointer is None:
            raise ResumeRejected("CHECKPOINT_NOT_CONFIGURED")
        graph = self._build_graph(on_event, safe_point, run_context)
        snapshot = graph.get_state(self._config(run_context))
        if "approval_wait" not in snapshot.next:
            raise ResumeRejected("RUN_NOT_WAITING_APPROVAL")
        return self._result(
            graph.invoke(
                Command(
                    resume={
                        "proposal_id": resolution.proposal_id,
                        "tool_call_id": resolution.tool_call_id,
                        "tool_name": resolution.tool_name,
                        "status": resolution.status,
                        "result": resolution.result,
                    }
                ),
                self._config(run_context),
            )
        )

    def _config(self, run_context: RunContext | None) -> dict:
        config = {"recursion_limit": 2 * self.config.max_steps + 10}
        if self._checkpointer is not None:
            if run_context is None:
                raise ValueError("durable workflow requires RunContext")
            config["configurable"] = {"thread_id": run_context.run_id}
        return config

    def _build_graph(self, on_event, safe_point, run_context=None):
        # Per-invocation graph callbacks avoid cross-run event leakage under concurrent gRPC.
        return build_workflow_graph(
            self._model,
            self._planner,
            self.registry,
            context_manager=self._context,
            max_steps=self.config.max_steps,
            max_tool_calls=self.config.max_tool_calls,
            on_event=on_event,
            checkpointer=self._checkpointer,
            safe_point=safe_point,
            memory_recall=(
                (
                    lambda query: self._memory_store.recall_with_ids(
                        run_context.memory_scope,
                        query,
                        max_chars=self.config.context_budget.max_memory_chars,
                    )
                )
                if self._memory_store is not None
                and run_context is not None
                and run_context.memory_scope is not None
                else None
            ),
        )

    def _result(self, state) -> WorkflowResult:
        if "__interrupt__" in state:
            pending = state.get("pending_proposal")
            if not pending:
                raise ResumeRejected("INVALID_APPROVAL_CHECKPOINT")
            raise ApprovalRequired(ToolProposal(**pending))
        observed = {
            message.name for message in state["messages"] if isinstance(message, ToolMessage)
        }
        tool_messages = [
            message for message in state["messages"] if isinstance(message, ToolMessage)
        ]
        selection = self._context.select(state["messages"])
        omitted = len(state["messages"]) - len(selection.messages)
        truncated_match = re.search(
            r"truncated=(\d+)", selection.summary or ""
        )
        truncated = int(truncated_match.group(1)) if truncated_match else 0
        sources = []
        for message in tool_messages:
            if message.name != "knowledge.search":
                continue
            try:
                data = json.loads(message.content)
            except (ValueError, TypeError):
                continue
            evidence = (
                data.get("sources", data.get("hits", []))
                if isinstance(data, dict) else []
            )
            for hit in evidence if isinstance(evidence, list) else []:
                source = hit.get("sourceFile") if isinstance(hit, dict) else None
                if isinstance(source, str) and source not in sources:
                    sources.append(str(self._redactor.redact(source))[:255])
        # Match Java StreamRun's existing final-output envelope, without protocol changes.
        # Java validates String.length (UTF-16 units), which differs from Python for emoji.
        safe_final = str(self._redactor.redact(state["final_answer"]))
        final = safe_final.encode("utf-16-le", errors="replace")[:130000].decode(
            "utf-16-le", errors="ignore"
        )
        return WorkflowResult(
            final,
            state["stop_reason"],
            state["planner_decision"],
            state["planner_elapsed_ms"],
            state["planner_call_count"],
            state["plan"],
            state["model_call_count"],
            state["tool_call_count"],
            state["delegation_count"],
            state["delegation_trace"],
            state.get("context_summary"),
            bool(observed.intersection(BUSINESS_TOOLS)),
            "knowledge.search" in observed,
            state.get("write_path_required", False),
            tuple(message.name for message in tool_messages if message.name),
            tuple(sources),
            len(state.get("memory_recall_ids", [])),
            omitted,
            truncated,
        )
