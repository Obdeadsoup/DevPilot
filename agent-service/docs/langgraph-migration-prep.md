# P2-00 / P2-01: Legacy characterization and LangGraph skeleton

## P2-00 behavior matrix

| Behavior | Existing test | Observable coverage | Gap |
|---|---|---|---|
| MODEL → FINAL | `test_direct_final_stops_without_tool`; `test_final_answer_persists_one_model_step_and_terminal_checkpoint` | final answer, model count, terminal run/checkpoint | None |
| MODEL → TOOL → MODEL → FINAL | `test_one_tool_call_returns_result_to_model_then_stops`; `test_one_tool_has_three_boundaries_and_reconstructable_protocol` | call count, ToolResult context, call ID, persistence boundaries | None |
| Multiple tools | `test_multiple_calls_in_one_response_execute_within_budget`; `test_partial_batch_remembers_completed_and_pending_calls` | order, arguments, pending/completed calls | None |
| max_steps | `test_max_steps_stops_model_that_keeps_requesting_tools`; persistence guard parameterization | model count, stop reason, failure projection | None |
| max_tool_calls | `test_over_budget_batch_executes_no_tool`; `test_tool_call_budget_is_cumulative_across_steps` | atomic batch rejection and cumulative budget | None |
| Duplicate tool_call_id | same-batch and cross-step duplicate tests | rejects before duplicate side effects | None |
| Model failure | model exception, provider kind, persistence and retry tests | failure code, retryable flag, stop reason | None |
| Tool failure | tool exception, persistence and transient resume tests | failure code, completion fact, retry behavior | None |
| Resume | restore, invalid checkpoint, exhausted budget and CAS tests | messages, counters, pending calls, concurrency | None |
| Completed tool no replay | `test_known_completed_call_in_explicit_pending_is_skipped` | completed call ID is not executed again | None |
| Cancel | loop safe-point, repository durability, RPC and race tests | intent, terminal CAS, preserved side effects | None |
| Stream disconnect != cancel | StartRun and ResumeRun disconnect tests | worker continues and commits success | None |
| Proposal approve/reject/expire | approval exact-args and parameterized resolution tests | no argument regeneration; rejected/expired/failed results | None |

The audit found no missing legacy behavior in the requested matrix, so P2-00 adds no duplicate
characterization tests. The new tests below cover only the new graph path and its parity boundary.

## A. Updated file map

Recommended reading order follows the list.

| Path | Responsibility | Called by / calls | Why changed |
|---|---|---|---|
| `pyproject.toml` | Direct dependency contract | Installer resolves LangGraph and message types | Make both imported packages explicit and bounded |
| `graph/state.py` | Minimal graph working state | Used by every node and `StateGraph` | Separate graph decisions from durable runtime facts |
| `graph/nodes/agent.py` | Existing Model ↔ LangChain message adapter | Calls `Model.generate`; returns one `AIMessage` update | Reuse the provider-neutral model boundary |
| `graph/nodes/tools.py` | Read-only ToolCall execution | Calls `ToolRegistry`; returns `ToolMessage` updates | Prove tool routing without moving Proposal/HITL |
| `graph/nodes/finalize.py` | Final result projection | Reads the final `AIMessage` | Keep finalization separate from DB lifecycle |
| `graph/routing.py` | Conditional decision after the model | Chooses `tools` or `finalize` | Replace an orchestration branch with an explicit edge decision |
| `graph/builder.py` | Graph assembly and compile | Wires nodes and edges; returns a compiled graph | Provide one isolated experimental entry point |
| `graph/__init__.py` | Small public API | Exports state and builder | Keep callers independent of internal module layout |
| `tests/test_langgraph_skeleton.py` | Skeleton behavior and supported parity | Uses FakeModel, ToolRegistry, EchoTool and AgentLoop | Verify cases A-C, parity, and the write-tool boundary |
| `README.md` | Migration status | Read by operators and contributors | State which runtime remains production and what is unsupported |

## B. Legacy → LangGraph mapping

| Legacy AgentLoop | LangGraph skeleton |
|---|---|
| `while True` loop | compiled graph execution |
| `next_action == "MODEL"` | `agent` node |
| `next_action == "TOOLS"` | `tools` node |
| orchestration `if/elif` | conditional and ordinary edges |
| local `messages` tuple | `messages` state channel |
| append assistant/tool messages | partial state updates reduced by `add_messages` |
| final branch | `finalize → END` |
| direct calls between phases | graph scheduler follows declared edges |
| RuntimeCheckpointState | deliberately remains in the Runtime Repository path |

This mapping is structural, not a claim of complete feature parity. The skeleton covers direct final
responses and read-only ReAct calls. Legacy still owns budgets, duplicate guards, persistence,
resume, cancel, events, Proposal/HITL and RPC behavior.

## C. Complete graph call chain

```text
invoke(initial state)
→ START
→ agent
→ route_after_agent
   ├─ AIMessage has tool_calls → tools → agent
   └─ AIMessage has no tool_calls → finalize → END
```

1. `invoke` receives `messages`, `run_id`, `tool_call_count`, `final_answer`, and `stop_reason`.
2. `agent` reads all messages, converts them to the existing internal `Message` model, calls
   `Model.generate(messages, registry.definitions())`, and returns only a new `AIMessage`.
3. `add_messages` merges that partial update into the message channel.
4. `route_after_agent` reads the last `AIMessage`. Structured calls choose `tools`; a final response
   chooses `finalize`.
5. `tools` reads only the latest call batch, verifies each registered tool is `READ_ONLY`, executes
   it through `ToolRegistry`, and returns `ToolMessage` objects plus the new tool-call count.
6. `add_messages` retains the assistant call message and associates every result by
   `tool_call_id`. The ordinary `tools → agent` edge starts the next model turn.
7. `finalize` reads the final `AIMessage` and updates `final_answer` and `stop_reason`.
8. `END` returns the accumulated state. No node writes Runtime DB rows or chooses its successor.

## D. State and reducer guide

State is the data visible to graph nodes while one invocation advances. This skeleton keeps only
model messages, a correlation-only `run_id`, a tool-call count, and final result fields. Operational
facts such as DB versions, worker IDs, cancellation intent and secrets do not belong here.

A node returns a partial update: it supplies only the keys it changed. For example, `agent` returns
`{"messages": [AIMessage(...)]}` and leaves counters and result fields alone.

A reducer defines how the graph combines an update with an existing channel. Without a reducer, a
new value replaces the old one. The `messages` annotation selects `add_messages`, which understands
LangChain messages and their IDs. It appends new messages while allowing a message with the same ID
to replace its prior version, preserving structured ToolCall/ToolResult relationships. A plain list
append has no message-ID replacement semantics and can duplicate updated messages.

## E. Key diff guide

1. **Dependency:** `langgraph>=1.2.11,<1.3` pins the requested stable minor line.
   `langchain-core>=1.2,<2` is direct because source code imports its message classes.
2. **State:** `DevPilotAgentState` uses a TypedDict and an annotated message reducer. It is not a
   copy of `RuntimeCheckpointState`.
3. **Agent node:** the narrow adapter converts graph messages to the existing provider-neutral
   messages and converts `ModelResponse` back to an `AIMessage`. It does not execute tools.
4. **Tool node:** it resolves and executes through `ToolRegistry`, serializes results exactly once,
   and constructs `ToolMessage` with the original call ID. Write-risk tools fail before execution.
5. **Routing:** `route_after_agent` is independently testable and depends only on the last model
   message, not on private node control flow.
6. **Builder:** `StateGraph` declares all three nodes, START/END edges, the conditional edge and the
   tool loop, then calls `compile()` without a checkpointer.
7. **Parity tests:** the supported read-tool scenario runs once through Legacy and once through the
   graph, comparing model rounds, tool name, call ID and final answer. Other tests cover direct
   final, one tool, multiple tools and write-tool refusal.

## F. Interview answers

### 1. Why not keep extending the while loop?

The loop already mixes routing with growing reliability policy. Explicit graph topology makes
control flow inspectable and lets later migration replace orchestration in bounded slices while the
proven repository, cancellation and approval behavior stays intact.

### 2. What problem does StateGraph solve?

It defines stateful execution as named nodes connected by explicit edges. The runtime applies node
updates, evaluates routing functions and schedules the next node, so transitions no longer depend
on one expanding procedural branch tree.

### 3. What are State, Node, Edge and Conditional Edge?

State is shared invocation data. A Node reads state and returns a partial update. An Edge declares a
fixed successor. A Conditional Edge calls a routing function and maps its result to a successor.

### 4. What does a reducer do?

A reducer combines an existing channel value with a node's update. It provides deterministic merge
semantics when replacement is not the desired behavior.

### 5. What do MessagesState and add_messages solve?

They provide message-aware accumulation. `add_messages` retains conversation order, normalizes
message values and replaces an existing message when an update carries the same ID, which is needed
for structured message workflows and cannot be expressed safely as unconditional list append.

### 6. What does compile() do?

It validates and turns the declared StateGraph into an executable runnable. The returned object
provides invocation and streaming APIs; it does not itself enable durable persistence unless a
checkpointer is explicitly supplied.

### 7. Why not connect a Checkpointer now?

P2-01 tests only orchestration shape. Introducing a second persistence mechanism now would mix graph
validation with recovery semantics and create two sources of truth before their lifecycle mapping is
designed.

### 8. Why can the existing SQLite Runtime Store not be deleted?

It records audited run and step facts, checkpoints, failure classification, cancellation intent and
CAS-protected terminal transitions used by production RPC behavior. The graph's in-memory state does
not replace those operational guarantees.

### 9. Why migrate only the read-only ReAct path first?

It proves model routing, tool result correlation and repeated model turns without risking business
writes. Write tools require Proposal/HITL, exact arguments, RBAC and idempotency, which need their own
parity phase.

### 10. Why are characterization tests important in a framework migration?

They specify externally visible behavior independently of the current loop implementation. The same
contract can test both runtimes and detect changes in call counts, tool effects, terminal results,
failure projection and replay behavior while orchestration internals change.
