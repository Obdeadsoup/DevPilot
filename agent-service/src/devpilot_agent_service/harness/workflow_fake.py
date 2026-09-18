"""Deterministic no-provider fake for exercising the actual production workflow ingress."""

import json
import time

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.planner.decision import PlannerReason, PlannerRoute
from devpilot_agent_service.runtime.message import MessageRole


class DeterministicWorkflowModel:
    def __init__(self, delay_seconds: float = 0, tool_name: str | None = None) -> None:
        self._delay = delay_seconds
        self._tool_name = tool_name

    def generate(self, messages, tools):
        if self._delay:
            time.sleep(self._delay)
        query = next(
            message.content for message in reversed(messages) if message.role is MessageRole.USER
        )
        if any(
            "You are the DevPilot query router." in message.content
            for message in messages
            if message.role is MessageRole.SYSTEM
        ):
            # Test/demo classification only; production uses the provider-backed QueryPlanner.
            documents = any(
                word in query.lower()
                for word in ("文档", "架构", "readme", "architecture", "outbox")
            )
            business = any(
                word in query.lower() for word in ("开放任务", "当前项目", "最近活动", "open tasks")
            )
            if self._tool_name == "knowledge.search":
                documents, business = True, False
            elif self._tool_name:
                documents, business = False, True
            route, reason = (
                (PlannerRoute.HYBRID, PlannerReason.MIXED_EVIDENCE)
                if documents and business
                else (PlannerRoute.ONLY_RAG, PlannerReason.PROJECT_DOCS)
                if documents
                else (PlannerRoute.ONLY_TOOL, PlannerReason.LIVE_STATE)
                if business
                else (PlannerRoute.DIRECT, PlannerReason.GENERAL)
            )
            return ModelResponse.final(
                json.dumps(
                    {
                        "route": route.value,
                        "rewritten_query": query[:2000],
                        "confidence": 1.0,
                        "reason_code": reason.value,
                    },
                    ensure_ascii=False,
                )
            )
        observed = {message.tool_name for message in messages if message.role is MessageRole.TOOL}
        available = {tool.name for tool in tools}
        business_tools = available - {"plan.update", "knowledge.search", "delegate.project_analyst"}
        if business_tools and not observed.intersection(business_tools):
            name = self._tool_name or "task.list_open"
            arguments = {} if name == "project.get_summary" else {"limit": 5}
            return ModelResponse.request_tools([ToolCall("fake-business-1", name, arguments)])
        return ModelResponse.final("fake-workflow:" + query + ":" + ",".join(sorted(observed)))
