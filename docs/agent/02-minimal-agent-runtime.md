# Agent 第 2 章：Message、Model、Tool 与最小 Agent Runtime

## 1. 本章目标

第 1 章只建立 Java/Python 进程、数据所有权和 RPC 契约边界。本章第一次在 `agent-service` 内加入可执行的
Agent Runtime 控制流：

```text
Message → Model → ToolCall → ToolRegistry → Tool
        ← Model ← Tool Result Message ←────────┘
        → Final / Stop
```

当前全部仍在 Python 单进程内，不存在 Java/Python RPC，也没有真实 LLM。自动化测试使用 FakeModel，把每轮模型
输出写成可重复脚本，因此不需要网络、API Key 或 Token。

## 2. LLM 调用与 Agent 的区别

一次普通 LLM 调用是“输入消息，返回文本或结构化输出”。Agent 还需要 Runtime 控制结构负责：保存上下文、向模型
公布可用工具、执行结构化 ToolCall、把 Tool Result 回填到下一轮，并在 Final、失败或硬上限时停止。

因此：

```text
LLM/Model = 每一轮决定下一步
AgentLoop = 保证多轮执行顺序、错误边界和停止条件
Tool      = Runtime 可调用的能力
```

## 3. Message：统一上下文原子

`runtime/message.py` 定义 `MessageRole` 与不可变 `Message`。Runtime 只传递这一内部类型，不在各层散落裸 `dict`，
也不传播 OpenAI、DeepSeek 等 SDK DTO。未来 Provider Adapter 负责在内部 Message 与供应商格式之间转换。

支持角色：`system`、`user`、`assistant`、`tool`。为了重建结构化工具协议，Message 只增加必要字段：

- assistant message 可携带 `ToolCall`；
- tool message 必须携带 `tool_call_id` 和 `tool_name`；
- 非 tool message 不能伪装成工具结果。

选择标准库 `dataclass(frozen=True, slots=True)`，因为本章只需要透明、不可变、低依赖的数据载体；没有复杂 JSON
校验或 HTTP 序列化需求，因此不引入 Pydantic。

## 4. Model abstraction

`model/base.py` 的 `Model` 是 Python `Protocol`。AgentLoop 只依赖：

```python
generate(messages, available_tools) -> ModelResponse
```

`ModelResponse` 严格区分两种结果：

- `FINAL`：模型已经给出最终答案；
- `TOOL_CALLS`：模型请求一个或多个结构化 `ToolCall`。

`ToolCall` 包含稳定 call id、工具名和 mapping 参数。Loop 不通过正则解析 `Action: echo`，具体 Provider Adapter
也不能把自己的 SDK 类型泄漏进 Runtime。本章没有真实 Provider Adapter，避免为了尚不存在的调用提前引入 SDK。

## 5. Tool 与 ToolRegistry

`tools/base.py` 的 `Tool` Protocol 至少要求 name、description、parameter schema 和 execute。Tool 是 Agent 可调用能力，
不是 DAO；本章唯一具体实现 `EchoTool` 只返回输入文本，不访问网络、文件、数据库或 DevPilot 业务服务。

`ToolRegistry` 类似 `Map<String, Tool> + Registry service`，统一负责：

- 注册、重复名拒绝、按名查询；
- 向模型导出只读 `ToolDefinition`；
- 参数 mapping 的基础约束；
- 保留 `InvalidToolArguments`；
- 把工具实现异常或非 JSON 兼容结果包装为 `ToolExecutionError`。

AgentLoop 不包含工具名 `if/elif`，因此新增工具不需要修改 Runtime 控制流。

## 6. Minimal Agent Loop

`runtime/agent_loop.py` 的同步主循环按以下顺序运行：

1. 可选追加 system message，再追加历史和本轮 user message；
2. 将不可变 messages 快照与 Tool definitions 交给 Model；
3. Model Final：追加 assistant message 并返回 `RunResult`；
4. Model ToolCalls：先追加带结构化调用的 assistant message；
5. 通过 ToolRegistry 逐个执行 ToolCall；
6. 将每个结果编码为 JSON tool message，并保留 call id/name；
7. 下一轮 Model 可以看到完整调用与结果；
8. 模型调用次数达到 `max_steps` 后抛出 `MaxStepsExceeded`。

主循环使用有上界的 `for`，没有无条件 `while True`。

### 无 Tool 调用链

```text
caller
→ AgentLoop.run
→ Model.generate(messages, definitions)
→ ModelResponse.FINAL
→ assistant Message
→ RunResult(MODEL_FINAL)
```

### 有 Tool 调用链

```text
caller
→ AgentLoop.run
→ Model.generate
→ ModelResponse.TOOL_CALLS
→ assistant ToolCall Message
→ ToolRegistry.execute
→ Tool.execute
→ JSON Tool Result Message
→ AgentLoop 下一 step
→ Model.generate（可见 Tool Result）
→ ModelResponse.FINAL
→ RunResult(MODEL_FINAL)
```

当前两条链路全部在 Python 单进程内，不存在 Java/Python RPC。未来 DevPilot 业务 Tool 必须通过 Java
`DevPilotToolGateway`，不能把当前本地 Tool 机制解释成数据库访问授权。

## 7. Stop Condition 与 Error Handling

| 停止语义 | 表达方式 | 含义 |
| --- | --- | --- |
| `MODEL_FINAL` | 成功 `RunResult` | 模型明确给出最终答案 |
| `MAX_STEPS` | `MaxStepsExceeded` | 达到模型调用硬上限 |
| `MODEL_ERROR` | `ModelInvocationError` | Model/Provider 调用异常 |
| `TOOL_ERROR` | `ToolExecutionError` | Tool 实现失败或返回不可编码结果 |
| `INVALID_TOOL_CALL` | `UnknownToolError` / `InvalidToolArguments` / `InvalidModelResponseError` | 模型请求未知工具、参数无效或响应结构非法 |

Runtime 不把所有异常变成 `"error"` 字符串继续喂给模型，也没有在本章加入 Retry、Backoff、Timeout 或 Circuit
Breaker。底层异常通过 Python exception chaining 保留给本地调试，但公开错误文本不拼接工具参数和底层异常内容。

## 8. 轻量 Runtime Trace

成功结果包含 `RuntimeTraceStep`：step number、response kind、tool names 和最终 stop reason。它只解释控制流，不保存
模型私有 reasoning/chain-of-thought，不做数据库持久化，也不构成完整 Observability 系统。

## 9. Agent Loop 与 ReAct

```text
Agent Loop = Runtime 控制结构，决定消息如何推进、工具如何执行以及何时停止
ReAct      = 每轮如何“思考/行动”的一种策略或范式
```

结构化 ToolCall 已经明确表达模型动作，因此当前 Runtime 不依赖 `Thought:/Action:` 自由文本协议。Plan-and-Solve、
Reflection 和复杂 ReAct parser 不属于本章。

## 10. 与 Java 的学习类比

| Python 设计 | Java 类比 | 注意 |
| --- | --- | --- |
| `Protocol` | `interface` | Python 使用结构化子类型，不要求显式 implements |
| frozen `dataclass` | `record` / 不可变 DTO | 只是理解类比，并非完全等价 |
| `ToolRegistry` | `Map<String, Tool>` + Registry service | Registry 还承担稳定错误边界 |
| `AgentLoop` | orchestration/application service + 有限状态机 | 当前同步、单进程、无事务 |
| `FakeModel` | Mockito Stub / Fake implementation | 脚本化返回让多轮行为完全可重复 |

不强行把 Python 写成 Java：Protocol、tuple 快照和 exception chaining 都使用 Python 自身惯用机制。

## 11. 本章明确不解决

- Java ↔ Python gRPC、Proto Stub、FastAPI、Browser SSE；
- 真实 OpenAI-compatible Provider、API Key、Retry、Timeout、Cancel；
- AgentRun Entity/Table、Checkpoint、Memory、RAG、MCP、Multi-Agent；
- Task/Project/GitHub Tool、Proposal/Confirm、跨服务认证；
- LangGraph、Plan-and-Solve、Reflection、复杂 ReAct parser；
- 任何 MySQL、`dp_*` 业务表或 Python 持久化访问。

下一章若接真实模型，应新增薄 Provider Adapter，把 SDK message/tool-call DTO 映射到本章内部类型；核心 Loop 和测试仍应
保持 Provider 无关。真正跨进程前还需单独设计 RPC metadata、超时、取消、错误详情、流式背压和服务身份。

## 12. 本地验证

```powershell
python -m pytest agent-service/tests
python -m ruff check agent-service
```

Ruff 位于 `dev` extra；核心 Runtime 没有新增第三方运行时依赖。
