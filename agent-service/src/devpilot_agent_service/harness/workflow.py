"""Query Planner workflow API; operational durability remains a Legacy responsibility."""

from collections.abc import Callable, Sequence
from dataclasses import dataclass

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.graph.workflow import BUSINESS_TOOLS, build_workflow_graph
from devpilot_agent_service.harness.prompts import UNTRUSTED_DATA_GUARD
from devpilot_agent_service.harness.runtime import HarnessConfig, HarnessRuntime
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.planner import QueryPlanner
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.events import RuntimeEvent
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.registry import ToolRegistry

WORKFLOW_PROMPT = (
    "You are the DevPilot read-oriented assistant. "
    + UNTRUSTED_DATA_GUARD
    + "The explicit Query Planner selects the route; use only this node's supplied capabilities. "
    "plan.update, if available, manages todos and never changes the route. "
    "Do not perform or claim business writes; write path requires legacy durable runtime."
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
    ) -> None:
        super().__init__(model, registry, analyst_model_factory, config=config, redactor=redactor)
        self._model = model
        self._context = ContextManager(self.config.context_budget, redactor=self._redactor)
        self._planner = QueryPlanner(
            planner_model or model, self._context, timeout_seconds=planner_timeout_seconds
        )

    def invoke(
        self,
        user_input: str,
        *,
        run_context: RunContext | None = None,
        history: Sequence[BaseMessage] = (),
        on_event: Callable[[RuntimeEvent], None] | None = None,
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
        # Per-invocation graph callbacks avoid cross-run event leakage under concurrent gRPC.
        graph = build_workflow_graph(
            self._model,
            self._planner,
            self.registry,
            context_manager=self._context,
            max_steps=self.config.max_steps,
            max_tool_calls=self.config.max_tool_calls,
            on_event=on_event,
        )
        state = graph.invoke(
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
            },
            {"recursion_limit": 2 * self.config.max_steps + 10},
        )
        observed = {
            message.name for message in state["messages"] if isinstance(message, ToolMessage)
        }
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
        )
