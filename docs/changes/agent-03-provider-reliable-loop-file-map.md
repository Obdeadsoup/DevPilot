# Agent 第 3 章文件地图

## 真实基线与范围

- 分支：`agent`
- 指令基线：`e80b80b05275293a54ca2e0402ca155566ceaa8c`
- 实际工作起点：`850501536758d85897fc8647565b8c721f1d394a`
- 起始用户修改：根 `README.md` 已修改、根 `architecture.md` 未跟踪；本章均未触碰。
- 范围：Python OpenAI-compatible Provider、可靠 Loop、测试、smoke 和学习文档；无 Java、RPC、数据库或业务 Tool。

## 逐文件说明

### [NEW] `agent-service/src/devpilot_agent_service/model/errors.py`

- 职责/模块：Model 边界的稳定 Provider 错误分类。
- 调用关系：Provider Adapter 创建；AgentLoop 捕获；调用 Python 内置异常。
- 关键方法：`ProviderErrorKind`、`ProviderError`、`ProviderConfigurationError`。
- 错误边界：把 SDK 细节收敛为 AUTH/RATE_LIMIT/TIMEOUT/UNAVAILABLE/PROTOCOL/UNKNOWN。
- 状态边界：无可变状态。
- 安全边界：异常文本不包含底层异常、Secret 或 Payload。
- 对应测试：`test_openai_compatible_model.py`、`test_agent_loop.py`。

### [NEW] `agent-service/src/devpilot_agent_service/model/providers/__init__.py`

- 职责/模块：标记 Provider Adapter 子包。
- 调用关系：供 Python import 系统加载；不调用业务代码。
- 关键方法：无。
- 错误边界：无。
- 状态边界：无。
- 安全边界：不导出 Client 或 Secret。
- 对应测试：由 Adapter import 测试间接覆盖。

### [NEW] `agent-service/src/devpilot_agent_service/model/providers/config.py`

- 职责/模块：Provider 配置边界，提供 DeepSeek 默认值和环境覆盖。
- 调用关系：smoke/组装层调用；读取 `os.environ`，构造配置值对象。
- 关键方法：`OpenAICompatibleConfig.from_deepseek_env()`、`__post_init__()`。
- 错误边界：缺 Key 或空 URL/model 抛脱敏 `ProviderConfigurationError`。
- 状态边界：冻结值对象，不缓存或修改环境。
- 安全边界：`api_key` 排除在 `repr` 外，只读取三个约定变量。
- 对应测试：环境默认、覆盖、缺 Key 和 Secret 不回显测试。

### [NEW] `agent-service/src/devpilot_agent_service/model/providers/openai_compatible.py`

- 职责/模块：Model Adapter；是 `src` 中唯一 OpenAI SDK 隔离点。
- 调用关系：AgentLoop 通过 Model Protocol 调用；内部调用 OpenAI-compatible Chat Completions Client。
- 关键方法：`generate()`、`to_provider_message()`、`to_provider_tool()`、响应归一化和异常分类。
- 错误边界：非法响应归为 PROTOCOL；真实 SDK 异常映射稳定 kind；未知异常脱敏。
- 状态边界：只持有冻结配置和 Client；每次 generate 构造新请求，不保存会话状态。
- 安全边界：`max_retries=0`；不把 SDK DTO/异常文本/请求 Payload 泄漏到 Runtime。
- 对应测试：消息/schema 双向映射、响应矩阵、真实 SDK 异常类型分类、脱敏测试。

### [MOD] `agent-service/src/devpilot_agent_service/runtime/errors.py`

- 职责/模块：Runtime 稳定停止原因与可捕获错误。
- 调用关系：AgentLoop 和 ToolRegistry 创建；上层 caller 捕获；引用 Provider 稳定 kind 而非 SDK。
- 关键方法：`ModelInvocationError`、`MaxToolCallsExceeded`、`DuplicateToolCallIdError`。
- 错误边界：新增 `MAX_TOOL_CALLS`；重复 id 归为 `INVALID_TOOL_CALL`。
- 状态边界：错误只携带 step、预算或分类等最小元数据。
- 安全边界：重复 id 不回显模型生成的原始值。
- 对应测试：Provider kind 透传、预算和重复 id StopReason 测试。

### [MOD] `agent-service/src/devpilot_agent_service/runtime/agent_loop.py`

- 职责/模块：同步有限状态编排与 ToolCall 可靠性闸门。
- 调用关系：caller 调用；Loop 调 Model Protocol、ToolRegistry；不 import Provider SDK。
- 关键方法：`AgentLoop.__init__()`、`run()`。
- 错误边界：Provider kind 包装为 `ModelInvocationError`；非法模型响应、工具错误、预算和重复 id 分开停止。
- 状态边界：每个 run 独立维护 messages、trace、累计 ToolCall 数和已执行 id 集合。
- 安全边界：重复/超预算批次先整体拒绝再执行；Trace 不保存 reasoning、Secret 或完整参数。
- 对应测试：第 2 章全部回归 + 单批多调用、跨轮累计、批内/跨轮重复和 Provider 错误测试。

### [NEW] `agent-service/tests/fakes/fake_openai_client.py`

- 职责/模块：测试专用 SDK Client 外形，记录请求并脚本化返回/抛错。
- 调用关系：Adapter 测试注入；不调用网络。
- 关键方法：`FakeCompletions.create()`。
- 错误边界：按测试脚本原样抛出异常，便于验证 Adapter 分类。
- 状态边界：仅保存请求快照和一个结果。
- 安全边界：固定测试数据，无 Key、Token 或外部 IO。
- 对应测试：`test_openai_compatible_model.py` 全部用例。

### [NEW] `agent-service/tests/test_openai_compatible_model.py`

- 职责/模块：Provider Adapter 契约矩阵。
- 调用关系：pytest 调 Adapter/Fake Client；引用真实 OpenAI SDK 异常类和本地 httpx2 request/response 对象。
- 关键方法：消息映射、schema、Final/ToolCall、protocol failure、错误分类测试。
- 错误边界：断言每种不可信响应和 SDK 失败得到准确稳定 kind。
- 状态边界：每个测试创建独立 Client/config，无共享状态。
- 安全边界：禁止网络；断言配置和异常不泄漏测试 Secret。
- 对应测试：文件自身。

### [MOD] `agent-service/tests/test_agent_loop.py`

- 职责/模块：AgentLoop 行为与可靠性回归。
- 调用关系：pytest 调 Loop、FakeModel、ToolRegistry；CountingTool 记录实际执行次数。
- 关键方法：预算、重复 id、Provider kind 和原有 Final/Tool/error/max_steps 测试。
- 错误边界：断言稳定 StopReason、cause 和超限批次零执行。
- 状态边界：CountingTool 证明 run 级累计与整批预检。
- 安全边界：无网络、无数据库、无真实业务副作用。
- 对应测试：文件自身。

### [NEW] `agent-service/examples/deepseek_tool_smoke.py`

- 职责/模块：真实 DeepSeek → native ToolCall → Echo → Final 的手工入口。
- 调用关系：人工 caller 调 Loop/Adapter/DeepSeek；Loop 调本地 EchoTool。
- 关键方法：`main()`。
- 错误边界：缺 Key=NOT RUN；网络/服务不可用=BLOCKED；其他 API/Runtime 错误=FAIL；完成=PASS。
- 状态边界：单进程、单 run、最多 4 steps/2 ToolCalls，不持久化。
- 安全边界：Key 只读环境变量，输出不打印配置、请求或工具参数。
- 对应测试：不进入 pytest；由人工 smoke 状态记录验收。

### [MOD] `agent-service/pyproject.toml`

- 职责/模块：Python 包、运行时和开发依赖声明。
- 调用关系：pip/build backend 读取；安装 `openai>=3.0,<4`。
- 关键方法：无。
- 错误边界：版本上界避免未经验证的下一主版本行为变化。
- 状态边界：声明式元数据。
- 安全边界：不包含凭据或 Provider 请求配置。
- 对应测试：隔离环境 editable install、pytest、Ruff。

### [MOD] `agent-service/README.md`

- 职责/模块：Agent Python 子工程入口说明。
- 调用关系：供开发者阅读；指向测试和 smoke 命令。
- 关键方法：无。
- 错误边界：说明 smoke 的状态语义。
- 状态边界：说明当前无 RPC/持久化等边界。
- 安全边界：只列环境变量名和非敏感默认值。
- 对应测试：文档命令由验收执行。

### [MOD] `.env.example`

- 职责/模块：本地环境变量模板。
- 调用关系：开发者复制到未提交 `.env`；代码不直接解析此文件。
- 关键方法：无。
- 错误边界：缺 Key 由 Provider config/smoke 明确处理。
- 状态边界：仅占位配置。
- 安全边界：Key 保持空值，不提交真实 Secret。
- 对应测试：配置环境映射测试与手工缺 Key smoke。

### [MOD] `.gitignore`

- 职责/模块：在现有 `docs/*` 规则下精准纳入本章两份文档。
- 调用关系：Git 工作树状态读取。
- 关键方法：无。
- 错误边界：只开放两个指定路径，未泛化追踪其他本地文档。
- 状态边界：不修改任何已存在文件内容。
- 安全边界：继续忽略 `.env`，只例外 `.env.example`。
- 对应测试：`git check-ignore`/`git status --short` 验证。

### [NEW] `docs/agent/03-provider-and-reliable-loop.md`

- 职责/模块：本章架构、可靠性和学习说明。
- 调用关系：供实现者/评审者阅读；引用内部代码和下一章 RPC 边界。
- 关键方法：无。
- 错误边界：解释 taxonomy 与不 Retry 的原因。
- 状态边界：解释 id 生命周期和双预算。
- 安全边界：强调模型输出不可信、Java Gateway 才是未来业务授权边界。
- 对应测试：文档描述与 Adapter/Loop 测试相互校验。

### [NEW] `docs/changes/agent-03-provider-reliable-loop-file-map.md`

- 职责/模块：本文件；逐文件记录改动与验收状态。
- 调用关系：供代码评审和学习复盘读取。
- 关键方法：无。
- 错误边界：显式记录真实命令结果，不把未运行项目写成 PASS。
- 状态边界：记录实际基线与用户既有工作树状态。
- 安全边界：不记录环境变量值、请求体或私有 Payload。
- 对应测试：最终验证清单。

## 调用链

```text
pytest → AgentLoop → FakeModel → ToolRegistry → EchoTool → Final
```

```text
manual caller → AgentLoop → OpenAICompatibleModel → DeepSeek → Final
```

```text
manual caller
→ AgentLoop
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

当前没有 Java/Python RPC。

## 验证记录

| 状态 | 命令/检查 | 实际结果 |
| --- | --- | --- |
| PASS | 隔离环境 `pip install -e "agent-service[dev]"` | 安装项目、OpenAI SDK 3.3.1、pytest、Ruff 成功 |
| PASS | `python -m pytest agent-service/tests` | 最终复验 57 passed in 1.91s |
| PASS | `python -m ruff check agent-service` | All checks passed |
| PASS | `python -m compileall -q agent-service/src agent-service/examples` | exit 0 |
| PASS | SDK import 边界扫描 | `src` 只有 `model/providers/openai_compatible.py` import `openai` |
| PASS | 禁止范围扫描 | Python src/example 未引入 gRPC、数据库 Client 或 `dp_*` 访问 |
| NOT RUN | `python agent-service/examples/deepseek_tool_smoke.py` | `DEEPSEEK_API_KEY is not set`，未发起网络请求 |
| PASS | `mvn clean verify` | 11 模块 `BUILD SUCCESS`；Docker 不可用，既有 Testcontainers 集成测试跳过 100 项 |
| PASS | `docker compose config` | exit 0，Compose 配置可解析 |
| PASS | `git diff --check` + 行尾空白扫描 | 无差异格式错误；`rg` 未找到行尾空白 |

真实 DeepSeek → ToolCall → EchoTool → Tool Result → DeepSeek Final 因当前环境没有 Key 而 `NOT RUN`，没有用
Fake 或手写 ToolCall 冒充真实 smoke。提供 Key 后应重新执行手工入口，并以脚本输出的 PASS / FAIL / BLOCKED
记录网络验收结果。
