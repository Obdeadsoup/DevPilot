"""Timeout/error boundary for an untrusted, provider-neutral routing model."""

import json
import math
import queue
import threading
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

from devpilot_agent_service.context import ContextManager
from devpilot_agent_service.graph.nodes.agent import _to_runtime_messages
from devpilot_agent_service.model.base import Model
from devpilot_agent_service.model.types import ModelResponse, ModelResponseKind
from devpilot_agent_service.planner.decision import PlannerDecision, PlannerReason, PlannerRoute
from devpilot_agent_service.planner.prompts import PLANNER_PROMPT

# A stuck injected provider cannot create unlimited background threads across concurrent runs.
# Production also sets the provider's network deadline; Python cannot force-kill a model thread.
_PROVIDER_SLOTS = threading.BoundedSemaphore(4)
_MODEL_REASONS = {
    PlannerReason.GENERAL,
    PlannerReason.LIVE_STATE,
    PlannerReason.PROJECT_DOCS,
    PlannerReason.MIXED_EVIDENCE,
}


def _unique_object(pairs):
    payload = {}
    for key, value in pairs:
        if key in payload:
            raise ValueError("duplicate planner JSON key")
        payload[key] = value
    return payload


@dataclass(frozen=True, slots=True)
class PlannerOutcome:
    decision: PlannerDecision
    model_call_count: int
    elapsed_ms: int
    context_summary: str | None


class QueryPlanner:
    def __init__(
        self,
        model: Model,
        context_manager: ContextManager,
        *,
        timeout_seconds: float = 5.0,
        min_confidence: float = 0.6,
    ) -> None:
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("planner timeout must be positive and finite")
        if not math.isfinite(min_confidence) or not 0 <= min_confidence <= 1:
            raise ValueError("planner confidence threshold must be between 0 and 1")
        self._model = model
        self._context = context_manager
        self._timeout = timeout_seconds
        self._min_confidence = min_confidence

    def plan(
        self, messages: Sequence[BaseMessage], *, on_model_start: Callable[[], None] | None = None
    ) -> PlannerOutcome:
        current = next(
            (
                message.content
                for message in reversed(messages)
                if isinstance(message, HumanMessage)
            ),
            None,
        )
        if not isinstance(current, str) or not current.strip():
            raise ValueError("planner requires a current user question")
        # Main/analyst system policy must not replace the router's distinct classification policy.
        bounded = self._context.select(
            [SystemMessage(content=PLANNER_PROMPT)]
            + [message for message in messages if not isinstance(message, SystemMessage)]
        )
        safe_query = str(self._context.redactor.redact(current)).strip()[:2000]
        started = time.monotonic()

        def outcome(reason: PlannerReason, calls: int) -> PlannerOutcome:
            return PlannerOutcome(
                PlannerDecision(PlannerRoute.HYBRID, safe_query, 0.0, reason),
                calls,
                int((time.monotonic() - started) * 1000),
                bounded.summary,
            )

        runtime_messages = _to_runtime_messages(bounded.messages)
        if not _PROVIDER_SLOTS.acquire(blocking=False):
            return outcome(PlannerReason.BUSY, 0)
        responses: queue.Queue[ModelResponse | None] = queue.Queue(maxsize=1)

        def generate() -> None:
            try:
                responses.put(self._model.generate(runtime_messages, ()))
            except Exception:
                responses.put(None)  # Never retain provider error bodies or secrets in state.
            finally:
                _PROVIDER_SLOTS.release()

        try:
            if on_model_start:
                on_model_start()
            worker = threading.Thread(target=generate, name="query-planner", daemon=True)
            worker.start()
        except Exception:
            _PROVIDER_SLOTS.release()
            raise
        try:
            response = responses.get(timeout=self._timeout)
        except queue.Empty:
            return outcome(PlannerReason.TIMEOUT, 1)
        if response is None:
            return outcome(PlannerReason.PROVIDER_ERROR, 1)
        try:
            decision = self._parse(response)
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            return outcome(PlannerReason.INVALID_OUTPUT, 1)
        if decision.confidence < self._min_confidence:
            return outcome(PlannerReason.LOW_CONFIDENCE, 1)
        return PlannerOutcome(
            decision, 1, int((time.monotonic() - started) * 1000), bounded.summary
        )

    def _parse(self, response: ModelResponse) -> PlannerDecision:
        if (
            not isinstance(response, ModelResponse)
            or response.kind is not ModelResponseKind.FINAL
            or len(response.content) > 4096
        ):
            raise ValueError("invalid planner response")
        payload = json.loads(response.content, object_pairs_hook=_unique_object)
        if not isinstance(payload, dict) or set(payload) != {
            "route",
            "rewritten_query",
            "confidence",
            "reason_code",
        }:
            raise ValueError("planner keys must match the routing schema")
        decision = PlannerDecision(
            PlannerRoute(payload["route"]),
            payload["rewritten_query"],
            payload["confidence"],
            PlannerReason(payload["reason_code"]),
        )
        if decision.reason_code not in _MODEL_REASONS:
            raise ValueError("fallback reasons are runtime-owned")
        return PlannerDecision(
            decision.route,
            str(self._context.redactor.redact(decision.rewritten_query)).strip()[:2000],
            float(decision.confidence),
            decision.reason_code,
        )
