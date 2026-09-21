"""Bound model input while retaining policy, task and complete tool protocol groups."""

import json
import re
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
        reasoning = (
            message.additional_kwargs.get("reasoning_content")
            if isinstance(message, AIMessage) else None
        )
        return len(content) + len(calls) + (len(reasoning) if isinstance(reasoning, str) else 0)

    def _bounded_tool_content(self, tool: ToolMessage) -> tuple[str, bool]:
        content = str(self.redactor.redact(tool.content))
        limit = (
            self.budget.max_rag_evidence_chars
            if tool.name == "knowledge.search"
            else self.budget.max_tool_result_chars
        )
        if len(content) <= limit:
            return content, False
        if tool.name == "knowledge.search":
            try:
                data = json.loads(content)
                evidence_key = (
                    "sources" if isinstance(data, dict) and isinstance(data.get("sources"), list)
                    else "hits"
                )
                hits = data.get(evidence_key) if isinstance(data, dict) else None
                if isinstance(hits, list):
                    bounded = []
                    for hit in hits:
                        if not isinstance(hit, dict):
                            continue
                        # Keep citation identity even when evidence text is shortened.
                        item = {
                            key: str(hit[key])[:200]
                            for key in ("sourceFile", "chunkId")
                            if key in hit
                        }
                        text_key = "content" if evidence_key == "sources" else "text"
                        text_limit = max(0, limit // max(1, len(hits)) - 250)
                        item[text_key] = str(hit.get(text_key, ""))[:text_limit]
                        bounded.append(item)
                    envelope = {
                        evidence_key: bounded,
                        "truncated": True,
                        "external_untrusted_content": True,
                    }
                    result = json.dumps(envelope, ensure_ascii=False)
                    while len(result) > limit and bounded:
                        bounded.pop()
                        result = json.dumps(envelope, ensure_ascii=False)
                    if len(result) <= limit:
                        return result, True
            except (TypeError, ValueError):
                pass
        marker = TRUNCATION_MARKER[:limit]
        return content[: max(0, limit - len(marker))] + marker, True

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
                        content, was_truncated = self._bounded_tool_content(tool)
                        if was_truncated:
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

    def incremental_summary(
        self,
        messages: Sequence[BaseMessage],
        previous: str | None,
        seen: Sequence[str] = (),
    ) -> tuple[str | None, list[str]]:
        """Summarize omitted conversation once; never promote Tool/RAG data."""
        try:
            selected = self.select(messages)
            kept = {id(message) for message in selected.messages}
            known = set(seen)
            updates = []
            for index, message in enumerate(messages):
                key = message.id or f"position:{index}"
                if key in known or id(message) in kept:
                    continue
                if isinstance(message, HumanMessage) or (
                    isinstance(message, AIMessage) and not message.tool_calls
                ):
                    updates.append(message)
                    known.add(key)
            if not updates:
                return previous, list(seen)
            data = (
                json.loads(previous)
                if previous and previous.startswith("{")
                else {"goal": "", "constraints": [], "facts": [], "open_items": []}
            )
            for message in updates:
                content = str(self.redactor.redact(message.content)).strip()
                if not content:
                    continue
                short = content[: min(180, self.budget.max_summary_chars // 4)]
                if isinstance(message, HumanMessage):
                    data["goal"] = short
                    if re.search(r"必须|不要|优先|must|never|prefer", short, re.I):
                        data["constraints"] = [short]
                    if re.search(r"待解决|尚未|还要|接下来|todo|next|\?$", short, re.I):
                        data["open_items"] = [short]
                else:
                    data["facts"] = [short]
            result = json.dumps(data, ensure_ascii=False, sort_keys=True)
            if len(result) > self.budget.max_summary_chars:
                data["goal"] = data["goal"][:100]
                data["constraints"] = [value[:100] for value in data["constraints"][-1:]]
                data["facts"] = [value[:100] for value in data["facts"][-1:]]
                result = json.dumps(data, ensure_ascii=False, sort_keys=True)
            return result, list(known)
        except (ValueError, TypeError, KeyError):
            return previous, list(seen)
