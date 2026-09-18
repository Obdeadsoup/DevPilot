import json
import threading

import pytest
from fakes.fake_model import FakeModel
from langchain_core.messages import HumanMessage, SystemMessage

from devpilot_agent_service.context import ContextBudget, ContextManager
from devpilot_agent_service.context.manager import ContextBudgetExceeded
from devpilot_agent_service.graph.workflow import route_after_planner
from devpilot_agent_service.model.errors import ProviderError, ProviderErrorKind
from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.planner import PlannerDecision, PlannerRoute, QueryPlanner
from devpilot_agent_service.planner.decision import PlannerReason
from devpilot_agent_service.runtime.redaction import RuntimeRedactor


def decision_response(route="DIRECT", **changes):
    value = {
        "route": route,
        "rewritten_query": "standalone project query",
        "confidence": 0.9,
        "reason_code": "GENERAL",
    }
    value.update(changes)
    return ModelResponse.final(json.dumps(value, ensure_ascii=False))


@pytest.mark.parametrize("route", list(PlannerRoute))
def test_explicit_planner_decision_and_conditional_edge(route):
    model = FakeModel([decision_response(route.value)])
    result = QueryPlanner(model, ContextManager()).plan(
        [SystemMessage(content="irrelevant main policy"), HumanMessage(content="current question")]
    )
    assert result.decision.route is route
    assert route_after_planner({"planner_route": result.decision.route.value}) == route.value
    assert model.calls[0].tools == ()
    assert "query router" in model.calls[0].messages[0].content
    assert all("irrelevant main policy" not in m.content for m in model.calls[0].messages)
    assert result.model_call_count == 1
    assert result.elapsed_ms >= 0


@pytest.mark.parametrize(
    "response",
    [
        ModelResponse.final("not JSON"),
        ModelResponse.final("[]"),
        ModelResponse.final("{}"),
        decision_response("UNKNOWN"),
        decision_response(confidence=True),
        decision_response(confidence=-0.1),
        decision_response(confidence=1.1),
        decision_response(confidence=float("nan")),
        decision_response(confidence=float("inf")),
        decision_response(rewritten_query=""),
        decision_response(rewritten_query="x" * 2001),
        decision_response(reason_code="private reasoning"),
        decision_response(reason_code="TIMEOUT"),
        decision_response(runId="forged"),
        decision_response(requestId="forged"),
        decision_response(userId="forged"),
        ModelResponse.final("x" * 4097),
        ModelResponse.final("[" * 1500 + "]" * 1500),
        ModelResponse.final(
            '{"route":"DIRECT","route":"HYBRID","rewritten_query":"query",'
            '"confidence":1,"reason_code":"GENERAL"}'
        ),
        decision_response(rewritten_query="\ud800"),
        ModelResponse.request_tools([ToolCall("1", "task.list_open", {})]),
    ],
)
def test_untrusted_invalid_output_falls_back_to_bounded_hybrid(response):
    result = QueryPlanner(FakeModel([response]), ContextManager()).plan(
        [HumanMessage(content="original question")]
    )
    assert result.decision.route is PlannerRoute.HYBRID
    assert result.decision.reason_code is PlannerReason.INVALID_OUTPUT
    assert result.decision.rewritten_query == "original question"
    assert set(result.decision.to_dict()) == {
        "route",
        "rewritten_query",
        "confidence",
        "reason_code",
    }


@pytest.mark.parametrize(
    "response, reason",
    [
        (decision_response(confidence=0.59), PlannerReason.LOW_CONFIDENCE),
        (ProviderError(ProviderErrorKind.TIMEOUT), PlannerReason.PROVIDER_ERROR),
        (RuntimeError("must not retain error body"), PlannerReason.PROVIDER_ERROR),
    ],
)
def test_low_confidence_and_provider_failure_are_fail_safe(response, reason):
    result = QueryPlanner(FakeModel([response]), ContextManager()).plan(
        [HumanMessage(content="fallback query")]
    )
    assert result.decision.reason_code is reason
    assert result.decision.route is PlannerRoute.HYBRID
    assert "error body" not in str(result)


def test_planner_timeout_returns_without_waiting_for_blocked_provider():
    release = threading.Event()
    exited = threading.Event()

    class BlockingModel:
        def generate(self, messages, tools):
            try:
                assert release.wait(2)
                return decision_response()
            finally:
                exited.set()

    try:
        result = QueryPlanner(BlockingModel(), ContextManager(), timeout_seconds=0.02).plan(
            [HumanMessage(content="query")]
        )
        assert result.decision.reason_code is PlannerReason.TIMEOUT
        assert result.elapsed_ms < 1000
        assert result.model_call_count == 1
    finally:
        release.set()
        assert exited.wait(2)


def test_planner_busy_does_not_start_extra_provider_threads(monkeypatch):
    import devpilot_agent_service.planner.query as module

    slots = threading.BoundedSemaphore(1)
    slots.acquire()
    monkeypatch.setattr(module, "_PROVIDER_SLOTS", slots)
    model = FakeModel([])
    result = QueryPlanner(model, ContextManager()).plan([HumanMessage(content="query")])
    assert result.decision.reason_code is PlannerReason.BUSY
    assert result.model_call_count == 0
    assert not model.calls


def test_router_context_budget_and_redaction_apply_before_result_is_exposed():
    model = FakeModel([decision_response(rewritten_query="secret-value project docs")])
    manager = ContextManager(redactor=RuntimeRedactor(("secret-value",)))
    decision = QueryPlanner(model, manager).plan([HumanMessage(content="project docs")]).decision
    assert "secret-value" not in decision.rewritten_query
    tiny = ContextManager(ContextBudget(max_context_chars=2000, reserved_output_chars=100))
    with pytest.raises(ContextBudgetExceeded):
        QueryPlanner(FakeModel([]), tiny).plan([HumanMessage(content="x" * 3000)])


@pytest.mark.parametrize("route", ["UNKNOWN", None, ""])
def test_conditional_edge_rejects_unvalidated_route(route):
    with pytest.raises(ValueError):
        route_after_planner({"planner_route": route})


def test_decision_rejects_invalid_authority_free_fields():
    with pytest.raises(ValueError):
        PlannerDecision(PlannerRoute.DIRECT, "query", True, PlannerReason.GENERAL)
