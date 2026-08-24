# 第 3 章：真实 Provider 与可靠 Agent Loop

## 1. 本章边界

第 2 章用 `FakeModel` 证明了 Runtime 的状态机：模型要么返回 Final，要么返回结构化 ToolCall；Runtime
执行工具、写回 Observation，再进入下一轮。Fake 足以快速、确定地验证编排规则，却不能证明真实 Provider 的
消息格式、function schema、响应 DTO 和异常类型能正确接入。

本章新增一个 OpenAI-compatible Adapter，默认使用 DeepSeek，并只补齐四个生产边界：Provider 协议校验、
`max_tool_calls`、重复 `tool_call_id` 防护和稳定错误分类。没有实现 Java/Python RPC、业务 Tool、数据库访问、
Retry、Checkpoint、SSE、RAG、Memory 或 Multi-Agent。

```text
manual caller
  → AgentLoop
  → Model Protocol
  → OpenAICompatibleModel
  → DeepSeek
  → provider tool_calls
  → internal ToolCall
  → ToolRegistry
  → EchoTool
  → Tool Result
  → DeepSeek
  → Final
```

`AgentLoop` 只认识 `Model Protocol`。DeepSeek 只是当前 Adapter 的默认配置，因此未来更换另一个兼容 Provider
不需要修改 Runtime。

## 2. Adapter 是 SDK 隔离墙

`model/providers/openai_compatible.py` 是 `src` 中唯一允许 import `openai` 的文件。SDK Client、请求 DTO、
响应 DTO 和 SDK 异常都在这里终止；Runtime 只接收内部 `Message`、`ToolDefinition`、`ModelResponse`、
`ToolCall` 和 `ProviderErrorKind`。

双向映射如下：

| 内部类型 | Provider 表达 | 不变量 |
| --- | --- | --- |
| `SYSTEM/USER/ASSISTANT` | 对应 role + content | content 不被拼成提示词协议 |
| assistant + `tool_calls` | assistant message + function calls | 保留 id、name，arguments 稳定 JSON 编码 |
| `TOOL` | tool message | 必须携带原始 `tool_call_id` |
| `ToolDefinition` | function tool schema | name、description、parameter_schema 原样表达 |
| Provider final | `ModelResponse.FINAL` | content 必须是字符串 |
| Provider tool calls | `ModelResponse.TOOL_CALLS` | id/name 非空，arguments 必须是 JSON object |

传统教学版 ReAct 用 `Thought/Action` 文本表示动作；native tool calling 用结构化 `tool_calls` 表示 Action。
二者的控制流仍然是“模型决策 → Action → Observation → 下一轮模型”，但本实现不解析自由文本 Thought，
也不记录或暴露私有 reasoning。

## 3. `tool_call_id` 的生命周期

Provider 为一次 function call 生成 id；Adapter 把它放进内部 `ToolCall.call_id`；Runtime 执行 Tool 后，
再把同一个 id 写入 Tool Result。下一次 Provider 调用依靠它把 Observation 与原 Action 对齐，因此不能用工具名
代替，也不能丢失。

一次 run 还维护已执行 id 集合。每批调用执行前先检查：

1. 当前批次内部是否有重复 id；
2. 是否重用了前面轮次已经执行的 id；
3. 整批加入后是否超过 ToolCall 预算。

任何检查失败，该响应中的 Tool 一个也不执行。这一整批预检避免“前一个有副作用 Tool 已执行，后一个才发现
重复或超限”的部分执行问题。真实业务 Tool 将来仍需在 Java Tool Gateway 实现幂等、RBAC、Scope 和业务规则；
LLM ToolCall 只是模型建议，不是授权指令。

## 4. 两条独立硬停止线

- `max_steps` 限制一次 run 最多调用模型多少轮，阻止模型持续请求工具形成无限循环。
- `max_tool_calls` 限制一次 run 最多执行多少个 Tool。一次模型响应可以包含多个 ToolCall，所以它不能被
  `max_steps` 替代。

两者都要求正整数。ToolCall 数量按 run 累计；超限在执行额外 Tool 之前抛出 `MaxToolCallsExceeded`，稳定
停止原因为 `MAX_TOOL_CALLS`。

## 5. Provider failure taxonomy

Adapter 不把 SDK 异常文本、请求体、API Key 或完整 Tool 参数交给上层，只保留 exception chaining 和稳定分类：

| 分类 | 典型 SDK 失败 | 当前行为 |
| --- | --- | --- |
| `AUTH` | `AuthenticationError` | 失败，不重试 |
| `RATE_LIMIT` | `RateLimitError` | 失败，不重试 |
| `TIMEOUT` | `APITimeoutError` | 失败，不重试 |
| `UNAVAILABLE` | 连接失败、服务端 5xx | 失败，不重试 |
| `PROTOCOL` | 缺 id/name、非法 arguments、非法响应结构 | 拒绝执行 |
| `UNKNOWN` | 其他 SDK/Client 失败 | 脱敏后失败 |

OpenAI SDK 自带隐式自动重试，本 Adapter 创建 Client 时显式设置 `max_retries=0`。模型请求的 Retry 不是普通
HTTP Retry：第一次调用可能已经计费或生成 ToolCall，Tool 也可能已经执行；盲目重试会影响预算，并可能重复产生
副作用。因此本章只做“识别、分类、失败”，把 Retry、Backoff、Cancel、Deadline 和幂等语义留给后续专门的
失败模型设计。

## 6. 配置与安全

只读取以下环境变量：

```text
DEEPSEEK_API_KEY      必填
DEEPSEEK_BASE_URL     默认 https://api.deepseek.com
DEEPSEEK_MODEL        默认 deepseek-v4-flash
```

Key 字段从配置对象 `repr` 排除，异常和 Trace 不输出 Key、请求 Payload 或 Tool 参数。`.env.example` 只列变量名
与非敏感默认值；真实值只放本机环境或未提交的 `.env`。

## 7. 自动测试与真实 smoke

自动测试链路完全确定且不访问网络：

```text
pytest → AgentLoop → FakeModel → ToolRegistry → EchoTool → Final
pytest → OpenAICompatibleModel → FakeOpenAIClient → normalized ModelResponse
```

Fake Client 仍使用真实 OpenAI SDK 异常类验证分类，但不创建网络连接。它覆盖所有消息方向、function schema、
Final/单调用/多调用、非法 arguments、缺失身份、预算、重复 id 和第 2 章回归。

手工 smoke 才验证真实边界：

```text
manual caller → AgentLoop → OpenAICompatibleModel → DeepSeek → Final
```

```powershell
python agent-service/examples/deepseek_tool_smoke.py
```

- 无 `DEEPSEEK_API_KEY`：`NOT RUN`；
- timeout/连接或 5xx：`BLOCKED`；
- auth、rate-limit、protocol 或其他 API 失败：`FAIL`；
- 完成 DeepSeek → EchoTool → DeepSeek Final：`PASS`。

网络 smoke 不属于默认 pytest 前提，避免 CI 因外网、配额和 Secret 不稳定而失去确定性。

## 8. 关键 Diff 导读

第 2 章的 FakeModel 用于证明 Runtime 算法；第 3 章的真实 Adapter 用于证明协议边界。SDK DTO 被挡在
`openai_compatible.py`，所以 `AgentLoop` 无需知道 DeepSeek。`ToolDefinition` 在 Adapter 中成为模型可见的
function schema；Provider 的 native tool call 在同一位置严格还原成内部 `ToolCall`。id 贯穿请求、执行和结果，
预算与重复检查则把不可信模型输出限制在明确的 Runtime 不变量内。Provider 错误只分类、不自动 Retry，是为了
先保持计费、工具副作用和预算语义可解释。

推荐按以下顺序阅读：

1. `model/base.py`：理解 Loop 依赖的最小协议；
2. `model/types.py`：理解 Provider-neutral 的响应；
3. `model/providers/openai_compatible.py`：看 SDK 隔离和双向映射；
4. `model/providers/config.py`：看 Secret 与默认 Provider 配置；
5. `runtime/message.py`：看上下文语义；
6. `runtime/agent_loop.py`：看预算和重复 id 整批预检；
7. `runtime/errors.py`：看稳定停止原因；
8. `tests/test_openai_compatible_model.py`：看 Adapter 边界矩阵；
9. `tests/test_agent_loop.py`：看 Loop 不变量；
10. `examples/deepseek_tool_smoke.py`：看真实手工入口；
11. 本文与文件地图：回看架构边界。

## 9. 下一章入口

下一章可以在保持内部协议不变的前提下，把进程内调用暴露为 Protobuf/gRPC：Java 调 Python
`AgentRuntime`，Python 调 Java `DevPilotToolGateway`。本章当前没有任何 Java/Python RPC；在跨进程边界建立前，
Python 不访问 `dp_*` 数据库，也没有真实 DevPilot 业务 Tool。
