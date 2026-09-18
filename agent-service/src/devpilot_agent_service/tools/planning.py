"""A bounded local todo list; never a Java Task or business write."""

from collections.abc import Mapping
from typing import Literal, TypedDict

from devpilot_agent_service.runtime.context import RunContext
from devpilot_agent_service.runtime.errors import InvalidToolArguments
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.workflow import WorkflowResult, WorkflowTool


class PlanItem(TypedDict):
    id: str
    title: str
    status: Literal["PENDING", "IN_PROGRESS", "DONE"]


class PlanningTool(WorkflowTool):
    name = "plan.update"
    description = (
        "Replace a short local workflow plan for multi-step analysis. "
        "Do not use for trivial one-step queries. This does not create business tasks."
    )
    parameter_schema = {
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "minItems": 1,
                "maxItems": 5,
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string", "minLength": 1, "maxLength": 32},
                        "title": {"type": "string", "minLength": 1, "maxLength": 160},
                        "status": {"type": "string", "enum": ["PENDING", "IN_PROGRESS", "DONE"]},
                    },
                    "required": ["id", "title", "status"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["items"],
        "additionalProperties": False,
    }

    def __init__(self, *, redactor: RuntimeRedactor | None = None) -> None:
        self._redactor = redactor or RuntimeRedactor()

    def execute(self, arguments: Mapping[str, object], **kwargs) -> dict:
        if set(arguments) != {"items"}:
            raise InvalidToolArguments(self.name, "only items is accepted")
        items = arguments["items"]
        if not isinstance(items, list) or not 1 <= len(items) <= 5:
            raise InvalidToolArguments(self.name, "items must contain 1 to 5 steps")
        validated = []
        ids = set()
        for item in items:
            if not isinstance(item, Mapping) or set(item) != {"id", "title", "status"}:
                raise InvalidToolArguments(self.name, "invalid plan item fields")
            identifier, title, status = item["id"], item["title"], item["status"]
            if (
                not isinstance(identifier, str)
                or not 1 <= len(identifier.strip()) <= 32
                or identifier.strip() in ids
                or not isinstance(title, str)
                or not 1 <= len(title.strip()) <= 160
                or status not in ("PENDING", "IN_PROGRESS", "DONE")
            ):
                raise InvalidToolArguments(self.name, "invalid or duplicate plan item")
            ids.add(identifier.strip())
            validated.append(
                {
                    "id": identifier.strip(),
                    "title": self._redactor.redact(title.strip()),
                    "status": status,
                }
            )
        return {"items": validated}

    def execute_in_graph(
        self, arguments, *, state, run_context: RunContext | None, tool_call_id: str
    ) -> WorkflowResult:
        result = self.execute(arguments)
        return WorkflowResult(result, {"plan": result["items"]})
