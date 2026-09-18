"""Deterministic offline scenario using the real Remote Tool adapters and a fake gateway."""

import json
from dataclasses import dataclass

from devpilot_agent_service.model.types import ModelResponse, ToolCall
from devpilot_agent_service.runtime.message import MessageRole

DEMO_QUERY = (
    "帮我分析当前项目情况，结合开放任务、最近活动和项目文档，"
    "找出当前最值得关注的三个问题，并给出下一步建议。"
)
PLAN_TITLES = (
    "获取项目概况",
    "分析开放任务和最近活动",
    "检索项目相关设计文档",
    "汇总问题与建议",
)
FINDINGS = (
    "两项高优先级任务仍开放：先确认负责人和交付时间。",
    "最近活动显示发布检查失败：先修复发布门禁。",
    "架构文档要求重试幂等：补齐失败重试的回归验证。",
)


@dataclass(frozen=True, slots=True)
class DemoModelCall:
    messages: tuple
    tools: tuple


class DemoGatewayClient:
    """No network or RBAC claim; records calls to existing remote adapters."""

    def __init__(self):
        self.calls = []

    def execute(self, context, call_id, name, arguments):
        self.calls.append((context, call_id, name, dict(arguments)))
        data = {
            "project.get_summary": {"name": "DevPilot", "openTaskCount": 2},
            "task.list_open": {
                "items": [
                    {"key": "DP-1", "title": "发布门禁", "priority": "HIGH"},
                    {"key": "DP-2", "title": "重试幂等验证", "priority": "HIGH"},
                ]
            },
            "project.list_recent_activity": {"items": [{"title": "发布检查失败"}]},
            "knowledge.search": {
                "hits": [
                    {
                        "sourceFile": "docs/architecture.md",
                        "chunkId": "demo-doc:0",
                        "text": "失败重试必须保持工具调用幂等。",
                    }
                ]
            },
        }[name]
        return {**data, "external_untrusted_content": True}


class DemoMainModel:
    def __init__(self):
        self.calls = []

    def generate(self, messages, tools):
        self.calls.append(DemoModelCall(tuple(messages), tuple(tools)))
        observations = [message for message in messages if message.role is MessageRole.TOOL]
        plans = [message for message in observations if message.tool_name == "plan.update"]
        if not plans:
            return self._plan("main-plan", "PENDING")
        if not any(message.tool_name == "delegate.project_analyst" for message in observations):
            return ModelResponse.request_tools(
                [
                    ToolCall(
                        "main-delegate",
                        "delegate.project_analyst",
                        {
                            "task": (
                                "综合项目概况、开放任务、最近活动和设计文档，"
                                "给出三个问题及下一步建议。"
                            )
                        },
                    )
                ]
            )
        if len(plans) < 2:
            return self._plan("main-plan-complete", "DONE")
        return ModelResponse.final(
            "当前最值得关注的三个问题：\n"
            + "\n".join(FINDINGS)
            + "\n依据：项目概况、开放任务、最近活动、docs/architecture.md。"
        )

    @staticmethod
    def _plan(call_id, status):
        return ModelResponse.request_tools(
            [
                ToolCall(
                    call_id,
                    "plan.update",
                    {
                        "items": [
                            {"id": str(i + 1), "title": title, "status": status}
                            for i, title in enumerate(PLAN_TITLES)
                        ]
                    },
                )
            ]
        )


class DemoAnalystModel:
    def __init__(self):
        self.calls = []

    def generate(self, messages, tools):
        self.calls.append(DemoModelCall(tuple(messages), tuple(tools)))
        if not any(message.role is MessageRole.TOOL for message in messages):
            return ModelResponse.request_tools(
                [
                    ToolCall("summary", "project.get_summary", {}),
                    ToolCall("tasks", "task.list_open", {"limit": 5}),
                    ToolCall("activity", "project.list_recent_activity", {"limit": 5}),
                    ToolCall(
                        "knowledge", "knowledge.search", {"query": "失败重试幂等设计", "topK": 3}
                    ),
                ]
            )
        return ModelResponse.final(
            json.dumps(
                {
                    "summary": "项目存在任务交付、发布门禁和重试可靠性三个关注点。",
                    "key_findings": list(FINDINGS),
                    "sources": ["docs/architecture.md"],
                    "warnings": [],
                },
                ensure_ascii=False,
            )
        )
