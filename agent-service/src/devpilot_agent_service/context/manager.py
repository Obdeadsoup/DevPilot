"""Bound model input while retaining policy, task and complete tool protocol groups."""

import json
from collections.abc import Sequence
from dataclasses import dataclass

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.context.budget import ContextBudget
from devpilot_agent_service.runtime.redaction import RuntimeRedactor

TRUNCATION_MARKER = "[tool result truncated by context budget]"


class ContextBudgetExceeded(ValueError):
    """Protected policy/task cannot fit; refuse instead of silently changing them."""


@dataclass(frozen=True, slots=True)
class ContextSelection:
    messages: tuple[BaseMessage, ...]
    summary: str | None  # Counts only; no source text or secrets in fallback summaries.


class ContextManager:
    def __init__(
        self, budget: ContextBudget | None = None, *, redactor: RuntimeRedactor | None = None
    ) -> None:
        self.budget = budget or ContextBudget()
        self.redactor = redactor or RuntimeRedactor()

    @staticmethod
    def message_chars(message: BaseMessage) -> int:
        content = message.content
        if not isinstance(content, str):
            raise TypeError("context manager supports string message content")
        calls = (
            json.dumps(message.tool_calls, ensure_ascii=False)
            if isinstance(message, AIMessage) and message.tool_calls
            else ""
        )
        return len(content) + len(calls)

    def select(self, messages: Sequence[BaseMessage]) -> ContextSelection:
        latest_user = next(
            (i for i in range(len(messages) - 1, -1, -1) if isinstance(messages[i], HumanMessage)),
            None,
        )
        protected = {i for i, message in enumerate(messages) if isinstance(message, SystemMessage)}
        if latest_user is not None:
            protected.add(latest_user)
        selected = {i: messages[i] for i in protected}
        used = sum(self.message_chars(message) for message in selected.values())
        if used > self.budget.input_chars:
            raise ContextBudgetExceeded("system policy and current task exceed context budget")

        # Keep AI tool calls and ALL matching results as an atomic protocol group. Never send
        # orphan ToolMessages or an unresolved tool-call message to Chat Completions.
        groups: list[list[tuple[int, BaseMessage]]] = []
        i = 0
        truncated = 0
        while i < len(messages):
            message = messages[i]
            if i in protected or isinstance(message, ToolMessage):
                i += 1
                continue
            group = [(i, message)]
            if isinstance(message, AIMessage) and message.tool_calls:
                expected = {call["id"] for call in message.tool_calls}
                j = i + 1
                results = []
                while j < len(messages) and isinstance(messages[j], ToolMessage):
                    tool = messages[j]
                    if tool.tool_call_id in expected:
                        content = str(self.redactor.redact(tool.content))
                        if len(content) > self.budget.max_tool_result_chars:
                            limit = self.budget.max_tool_result_chars
                            marker = TRUNCATION_MARKER[:limit]
                            content = content[: max(0, limit - len(marker))] + marker
                            truncated += 1
                        results.append((j, tool.model_copy(update={"content": content})))
                    j += 1
                if {tool.tool_call_id for _, tool in results} != expected:
                    i = j
                    continue
                group.extend(results)
                i = j
            else:
                i += 1
            groups.append(group)

        recent_count = 0
        for group in reversed(groups):
            size = sum(self.message_chars(message) for _, message in group)
            if (
                used + size > self.budget.input_chars
                or recent_count + len(group) > self.budget.max_recent_messages
            ):
                continue
            selected.update(group)
            used += size
            recent_count += len(group)
        result = tuple(message for _, message in sorted(selected.items()))
        omitted = len(messages) - len(result)
        summary = (
            f"context budget: omitted={omitted}, truncated={truncated}"
            if (omitted or truncated)
            else None
        )
        return ContextSelection(result, summary)
