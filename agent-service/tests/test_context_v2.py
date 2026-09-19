import json

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from devpilot_agent_service.context import ContextBudget, ContextManager


def test_incremental_summary_only_uses_omitted_conversation_and_deduplicates():
    manager = ContextManager(ContextBudget(
        max_context_chars=500, reserved_output_chars=100, max_recent_messages=1,
        max_summary_chars=300,
    ))
    messages = [
        SystemMessage(content="policy"),
        HumanMessage(content="以后请优先考虑已有 Java 服务"),
        AIMessage(content="已记录这个需求"),
        AIMessage(content="recent answer"),
        HumanMessage(content="当前问题"),
    ]
    summary, seen = manager.incremental_summary(messages, None)
    assert summary is not None
    data = json.loads(summary)
    assert "Java" in data["goal"]
    assert data["constraints"]
    assert "已记录" in data["facts"][0]
    again, seen_again = manager.incremental_summary(messages, summary, seen)
    assert again == summary
    assert seen_again == seen


def test_rag_truncation_preserves_source_identity():
    manager = ContextManager(ContextBudget(
        max_context_chars=1000, reserved_output_chars=100, max_recent_messages=5,
        max_rag_evidence_chars=250,
    ))
    messages = [
        SystemMessage(content="policy"),
        HumanMessage(content="explain document"),
        AIMessage(content="", tool_calls=[{
            "name": "knowledge.search", "args": {"query": "test"}, "id": "call-1",
            "type": "tool_call",
        }]),
        ToolMessage(
            name="knowledge.search", tool_call_id="call-1",
            content=json.dumps({"hits": [{
                "sourceFile": "architecture.md", "chunkId": "chunk-1",
                "text": "x" * 2000,
            }]}),
        ),
    ]
    selected = manager.select(messages)
    evidence = next(message for message in selected.messages if isinstance(message, ToolMessage))
    payload = json.loads(evidence.content)
    assert payload["hits"][0]["sourceFile"] == "architecture.md"
    assert payload["hits"][0]["chunkId"] == "chunk-1"
    assert payload["truncated"] is True
