"""Executable planning/context/delegation path without durable lifecycle migration."""

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from devpilot_agent_service.agents.project_analyst.agent import ProjectAnalyst
from devpilot_agent_service.agents.registry import PROJECT_ANALYST_TOOLS
from devpilot_agent_service.context import ContextBudget, ContextManager
from devpilot_agent_service.graph.builder import build_agent_graph
from devpilot_agent_service.harness.prompts import MAIN_PROMPT
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.rpc.tool_gateway_client import JavaToolGatewayClient
from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.devpilot import (
    KnowledgeSearchTool,
    ListOpenTasksTool,
    ProjectSummaryTool,
    RecentProjectActivityTool,
)
from devpilot_agent_service.tools.planning import PlanningTool
from devpilot_agent_service.tools.registry import ToolRegistry
from devpilot_agent_service.tools.subagents import DelegateProjectAnalystTool


@dataclass(frozen=True, slots=True)
class HarnessConfig:
    context_budget: ContextBudget = field(default_factory=ContextBudget)
    max_steps: int = 8
    max_tool_calls: int = 16
    max_delegations_per_run: int = 2
    subagent_max_steps: int = 6
    subagent_max_tool_calls: int = 8

    def __post_init__(self) -> None:
        if any(
            type(value) is not int or value < 1
            for value in (
                self.max_steps,
                self.max_tool_calls,
                self.max_delegations_per_run,
                self.subagent_max_steps,
                self.subagent_max_tool_calls,
            )
        ):
            raise ValueError("harness execution budgets must be positive integers")
        if self.max_delegations_per_run > 2:
            raise ValueError("Harness v1 allows at most two delegations per run")


@dataclass(frozen=True, slots=True)
class HarnessResult:
    final_answer: str
    stop_reason: str
    plan: list
    model_call_count: int
    tool_call_count: int
    delegation_count: int
    delegation_trace: list
    context_summary: str | None


class HarnessRuntime:
    def __init__(
        self,
        model: Model,
        registry: ToolRegistry,
        analyst_model_factory: Callable[[], Model],
        *,
        config: HarnessConfig | None = None,
        redactor: RuntimeRedactor | None = None,
    ) -> None:
        self.config = config or HarnessConfig()
        self._redactor = redactor or RuntimeRedactor()
        context = ContextManager(self.config.context_budget, redactor=self._redactor)
        # Snapshot exact capabilities; task.create and later registry additions cannot leak in.
        self.registry = registry.subset(PROJECT_ANALYST_TOOLS, read_only=True)
        analyst = ProjectAnalyst(
            analyst_model_factory,
            self.registry,
            context_manager=context,
            max_steps=self.config.subagent_max_steps,
            max_tool_calls=self.config.subagent_max_tool_calls,
            redactor=self._redactor,
        )
        self.registry.register(PlanningTool(redactor=self._redactor))
        self.registry.register(
            DelegateProjectAnalystTool(
                analyst,
                max_delegations=self.config.max_delegations_per_run,
                redactor=self._redactor,
            )
        )
        self._graph = build_agent_graph(
            model,
            self.registry,
            context_manager=context,
            max_steps=self.config.max_steps,
            max_tool_calls=self.config.max_tool_calls,
        )

    def invoke(
        self,
        user_input: str,
        *,
        run_context: RunContext | None = None,
        history: Sequence[BaseMessage] = (),
    ) -> HarnessResult:
        if not isinstance(user_input, str) or not user_input.strip():
            raise ValueError("harness query must not be blank")
        if run_context is not None and not isinstance(run_context, RunContext):
            raise TypeError("run_context must be RunContext")
        if any(
            not isinstance(message, BaseMessage) or isinstance(message, SystemMessage)
            for message in history
        ):
            raise ValueError("history must contain conversation messages, not system policy")
        state = self._graph.invoke(
            {
                "messages": [
                    SystemMessage(content=MAIN_PROMPT),
                    *history,
                    HumanMessage(content=user_input),
                ],
                "run_id": run_context.run_id if run_context else None,
                "request_id": run_context.request_id if run_context else None,
                "tool_call_count": 0,
                "model_call_count": 0,
                "plan": [],
                "context_summary": None,
                "delegation_count": 0,
                "delegation_trace": [],
                "final_answer": None,
                "stop_reason": None,
            },
            {"recursion_limit": 2 * self.config.max_steps + 3},
        )
        return HarnessResult(
            str(self._redactor.redact(state["final_answer"])),
            state["stop_reason"],
            state["plan"],
            state["model_call_count"],
            state["tool_call_count"],
            state["delegation_count"],
            state["delegation_trace"],
            state["context_summary"],
        )


def create_remote_harness(
    model: Model,
    client: JavaToolGatewayClient,
    analyst_model_factory: Callable[[], Model],
    *,
    config: HarnessConfig | None = None,
    redactor: RuntimeRedactor | None = None,
) -> HarnessRuntime:
    registry = ToolRegistry()
    for tool_type in (
        ProjectSummaryTool,
        ListOpenTasksTool,
        RecentProjectActivityTool,
        KnowledgeSearchTool,
    ):
        registry.register(tool_type(client))
    return HarnessRuntime(model, registry, analyst_model_factory, config=config, redactor=redactor)
