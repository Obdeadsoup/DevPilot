import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import HumanMessage

from devpilot_agent_service.graph import build_agent_graph
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.errors import InvalidToolArguments
from devpilot_agent_service.tools.planning import PlanningTool
from devpilot_agent_service.tools.registry import ToolRegistry


def item(identifier="1", title="Read project", status="PENDING"):
    return {"id": identifier, "title": title, "status": status}


@pytest.mark.parametrize(
    "arguments",
    [
        {"items": []},
        {"items": [item(str(i)) for i in range(6)]},
        {"items": [item(), item()]},
        {"items": [item(title="x" * 161)]},
        {"items": [item(status="UNKNOWN")]},
        {"items": [item(identifier=" ")]},
        {"items": [item()], "projectId": 99},
        {"items": "invalid"},
        {"items": [{**item(), "actor": 1}]},
    ],
)
def test_plan_schema_bounds_are_enforced_in_code(arguments):
    with pytest.raises(InvalidToolArguments):
        PlanningTool().execute(arguments)


def test_plan_updates_graph_state_and_model_can_continue():
    registry = ToolRegistry()
    registry.register(PlanningTool())
    items = [item("1"), item("2", "Analyze docs", "IN_PROGRESS")]
    model = FakeModel(
        [
            ModelResponse.request_tools([ToolCall("plan-1", "plan.update", {"items": items})]),
            ModelResponse.final("continued"),
        ]
    )
    result = build_agent_graph(model, registry).invoke(
        {
            "messages": [HumanMessage(content="Analyze project using several sources")],
            "run_id": None,
            "tool_call_count": 0,
            "final_answer": None,
            "stop_reason": None,
        }
    )
    assert result["plan"] == items
    assert result["final_answer"] == "continued"
    assert model.calls[1].messages[-1].tool_name == "plan.update"
    assert len(model.calls) == 2
    items[0]["title"] = "changed after execution"
    assert result["plan"][0]["title"] == "Read project"


def test_plan_supports_five_steps_and_done_without_shared_mutable_state():
    tool = PlanningTool()
    result = tool.execute({"items": [item(str(i), status="DONE") for i in range(5)]})
    assert len(result["items"]) == 5
    assert all(step["status"] == "DONE" for step in result["items"])
    assert "api_key=super-secret-value" not in str(
        tool.execute(
            {
                "items": [item(title="api_key=super-secret-value")],
            }
        )
    )
