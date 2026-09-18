"""Local specialist delegation with invocation-local accounting."""

from collections.abc import Mapping

from devpilot_agent_service.agents.project_analyst.agent import ProjectAnalyst
from devpilot_agent_service.runtime.errors import InvalidToolArguments
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.workflow import WorkflowResult, WorkflowTool


class DelegationBudgetExceeded(RuntimeError):
    def __init__(self) -> None:
        super().__init__("project analyst delegation budget exceeded")


class DelegateProjectAnalystTool(WorkflowTool):
    name = "delegate.project_analyst"
    description = (
        "Delegate broad read-only project analysis to an isolated Project Analyst. "
        "Pass a short standalone task, never the full conversation."
    )
    parameter_schema = {
        "type": "object",
        "properties": {"task": {"type": "string", "minLength": 1, "maxLength": 2000}},
        "required": ["task"],
        "additionalProperties": False,
    }

    def __init__(
        self,
        analyst: ProjectAnalyst,
        *,
        max_delegations: int = 2,
        redactor: RuntimeRedactor | None = None,
    ) -> None:
        if type(max_delegations) is not int or max_delegations < 1:
            raise ValueError("max_delegations must be a positive integer")
        self._analyst = analyst
        self._max_delegations = max_delegations
        self._redactor = redactor or RuntimeRedactor()

    def execute(self, arguments: Mapping[str, object], **kwargs):
        raise InvalidToolArguments(self.name, "delegation requires graph workflow state")

    def execute_in_graph(self, arguments, *, state, run_context, tool_call_id) -> WorkflowResult:
        count = state.get("delegation_count", 0)
        if count >= self._max_delegations:
            raise DelegationBudgetExceeded()
        task = arguments.get("task")
        if (
            set(arguments) != {"task"}
            or not isinstance(task, str)
            or not 1 <= len(task.strip()) <= 2000
        ):
            raise InvalidToolArguments(self.name, "task must contain 1 to 2000 characters")
        if run_context is None:
            raise InvalidToolArguments(self.name, "authoritative run context is required")
        analysis = self._analyst.invoke(
            str(self._redactor.redact(task.strip())),
            run_context=run_context,
            delegation_id=tool_call_id,
        )
        trace = [
            *state.get("delegation_trace", []),
            {
                "agent": "project_analyst",
                "tool_call_id": tool_call_id,
                "model_call_count": analysis.model_call_count,
                "tool_call_count": analysis.tool_call_count,
            },
        ]
        return WorkflowResult(
            analysis.handoff,
            {
                "delegation_count": count + 1,
                "delegation_trace": trace,
            },
        )
