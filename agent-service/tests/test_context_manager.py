import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.context import ContextBudget, ContextBudgetExceeded, ContextManager
from devpilot_agent_service.context.manager import TRUNCATION_MARKER
from devpilot_agent_service.graph import build_agent_graph
from devpilot_agent_service.model.types import ModelResponse
from devpilot_agent_service.runtime.message import MessageRole
from devpilot_agent_service.runtime.redaction import RuntimeRedactor
from devpilot_agent_service.tools.registry import ToolRegistry


def call_message(identifier):
    return AIMessage(
        content="",
        tool_calls=[
            {
                "name": "read",
                "id": identifier,
                "args": {},
                "type": "tool_call",
            }
        ],
    )


def test_100kb_tool_result_is_bounded_before_actual_model_invocation():
    raw = "x" * 100_000
    manager = ContextManager(ContextBudget(6000, 1000, 10, 1000))
    model = FakeModel([ModelResponse.final("bounded")])
    messages = [
        SystemMessage(content="Policy stays"),
        HumanMessage(content="Current user task"),
        call_message("c1"),
        ToolMessage(content=raw, tool_call_id="c1", name="read"),
    ]
    result = build_agent_graph(model, ToolRegistry(), context_manager=manager).invoke(
        {
            "messages": messages,
            "run_id": None,
            "tool_call_count": 0,
            "final_answer": None,
            "stop_reason": None,
        }
    )
    actual = model.calls[0].messages
    assert actual[0].content == "Policy stays"
    assert actual[1].content == "Current user task"
    assert actual[-1].role is MessageRole.TOOL
    assert len(actual[-1].content) <= 1000
    assert TRUNCATION_MARKER in actual[-1].content
    assert sum(len(message.content) for message in actual) <= manager.budget.input_chars
    assert messages[-1].content == raw
    assert "truncated=1" in result["context_summary"]


def test_recent_selection_keeps_complete_protocol_groups_and_latest_task():
    manager = ContextManager(ContextBudget(4000, 400, 3, 500))
    messages = [
        SystemMessage(content="policy"),
        HumanMessage(content="old question"),
        call_message("old"),
        ToolMessage(content="old result", tool_call_id="old"),
        HumanMessage(content="new task"),
        call_message("new"),
        ToolMessage(content="new result", tool_call_id="new"),
        ToolMessage(content="orphan", tool_call_id="unknown"),
    ]
    result = manager.select(messages)
    assert any(message.content == "new task" for message in result.messages)
    assert not any(message.content == "orphan" for message in result.messages)
    for index, message in enumerate(result.messages):
        if isinstance(message, AIMessage) and message.tool_calls:
            assert result.messages[index + 1].tool_call_id == message.tool_calls[0]["id"]


def test_protected_messages_over_budget_fail_without_silent_policy_or_task_truncation():
    manager = ContextManager(ContextBudget(100, 20, 2, 20))
    with pytest.raises(ContextBudgetExceeded, match="system policy and current task"):
        manager.select([SystemMessage(content="p" * 40), HumanMessage(content="q" * 50)])


def test_redaction_precedes_truncation_and_summary_contains_only_counts():
    manager = ContextManager(
        ContextBudget(1000, 150, 4, 100),
        redactor=RuntimeRedactor(("known-secret-value",)),
    )
    result = manager.select(
        [
            SystemMessage(content="policy"),
            HumanMessage(content="query"),
            call_message("c1"),
            ToolMessage(content="known-secret-value " + "x" * 1000, tool_call_id="c1", name="read"),
        ]
    )
    assert "known-secret-value" not in str(result)
    assert "[REDACTED]" in result.messages[-1].content
    assert result.summary == "context budget: omitted=0, truncated=1"


@pytest.mark.parametrize(
    "budget",
    [
        (0, 100, 2, 1),
        (100, 0, 2, 1),
        (100, 10, 0, 1),
        (100, 10, 2, 100),
    ],
)
def test_invalid_context_budgets_are_rejected(budget):
    with pytest.raises(ValueError):
        ContextBudget(*budget)
