"""Trusted local workflow capabilities, separate from business side effects."""

from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass

from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.tools.base import JsonValue, ToolRisk


@dataclass(frozen=True, slots=True)
class WorkflowResult:
    result: JsonValue
    update: dict[str, object]


class WorkflowTool(ABC):
    # READ_ONLY means no business mutation; these tools may update local graph workflow state.
    risk = ToolRisk.READ_ONLY

    @abstractmethod
    def execute_in_graph(
        self,
        arguments: Mapping[str, object],
        *,
        state: Mapping[str, object],
        run_context: RunContext | None,
        tool_call_id: str,
    ) -> WorkflowResult: ...
