"""Bounded routing data; no actor, scope, credentials or private reasoning."""

import math
from dataclasses import dataclass
from enum import StrEnum


class PlannerRoute(StrEnum):
    DIRECT = "DIRECT"
    ONLY_TOOL = "ONLY_TOOL"
    ONLY_RAG = "ONLY_RAG"
    HYBRID = "HYBRID"


class PlannerReason(StrEnum):
    GENERAL = "GENERAL"
    LIVE_STATE = "LIVE_STATE"
    PROJECT_DOCS = "PROJECT_DOCS"
    MIXED_EVIDENCE = "MIXED_EVIDENCE"
    INVALID_OUTPUT = "INVALID_OUTPUT"
    LOW_CONFIDENCE = "LOW_CONFIDENCE"
    PROVIDER_ERROR = "PROVIDER_ERROR"
    TIMEOUT = "TIMEOUT"
    BUSY = "BUSY"


@dataclass(frozen=True, slots=True)
class PlannerDecision:
    route: PlannerRoute
    rewritten_query: str
    confidence: float
    reason_code: PlannerReason

    def __post_init__(self) -> None:
        if not isinstance(self.route, PlannerRoute) or not isinstance(
            self.reason_code, PlannerReason
        ):
            raise ValueError("invalid planner enum")
        if not isinstance(self.rewritten_query, str) or not (
            1 <= len(self.rewritten_query.strip()) <= 2000
        ):
            raise ValueError("planner query must contain 1 to 2000 characters")
        try:
            self.rewritten_query.encode("utf-8")
        except UnicodeEncodeError as error:
            raise ValueError("planner query must be valid Unicode") from error
        if (
            type(self.confidence) not in (int, float)
            or not math.isfinite(self.confidence)
            or not 0 <= self.confidence <= 1
        ):
            raise ValueError("planner confidence must be finite and between 0 and 1")

    def to_dict(self) -> dict:
        return {
            "route": self.route.value,
            "rewritten_query": self.rewritten_query,
            "confidence": self.confidence,
            "reason_code": self.reason_code.value,
        }
