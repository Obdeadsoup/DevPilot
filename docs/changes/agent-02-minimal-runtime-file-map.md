# Agent 第 2 章文件地图：最小 Agent Runtime

## 1. 实际基线与结论

- 实际 HEAD：`6a836b90cf25e71a7abdf8faace66e06963ec11e`。
- 在第 1 章验收尚未提交的文档变更上继续实现，没有 checkout、restore、clean、stash 或覆盖用户工作。
- 第 2 章第一次加入真实 Python Agent Runtime 行为：统一 Message、Provider 无关 Model、结构化 ToolCall、
  ToolRegistry、Tool Result 回填、Final/错误停止和 `max_steps` 硬上限。
- 没有修改 Java、Proto、POM、Compose、数据库或 RPC。

## 2. 完整更新文件地图

### [NEW]

| 文件 | 职责/所属模块 | 谁调用它 → 它调用谁 | 关键类/函数 | 错误与状态边界 | 对应测试 |
| --- | --- | --- | --- | --- | --- |
| `agent-service/src/devpilot_agent_service/model/types.py` | Python Model 内部响应类型 | Provider/Fake、AgentLoop → 无外部系统 | `ToolCall`、`ModelResponse`、`ModelResponseKind` | 强制 Final/ToolCalls 二选一；复制只读参数 | `test_message.py`、`test_agent_loop.py` |
| `agent-service/src/devpilot_agent_service/model/base.py` | Provider 能力端口 | AgentLoop → `Model.generate` 实现 | `Model` Protocol | 不捕获 Provider 错误，由 Loop 分类 | `FakeModel` 间接覆盖 |
| `agent-service/src/devpilot_agent_service/runtime/message.py` | Runtime 上下文原子 | AgentLoop、Model Adapter → `ToolCall` | `MessageRole`、`Message` | Tool Result 必须关联 call id/name；不可变 | `test_message.py`、`test_agent_loop.py` |
| `agent-service/src/devpilot_agent_service/runtime/errors.py` | 稳定失败模型 | Loop/Registry/Tool → Python 调用方 | `StopReason` 与五类核心异常 | Final 成功返回；其余原因通过异常分类 | `test_tool_registry.py`、`test_agent_loop.py` |
| `agent-service/src/devpilot_agent_service/runtime/agent_loop.py` | 同步单 Agent 编排 | Python caller → Model、Registry、Message | `AgentLoop.run`、`RunResult`、`RuntimeTraceStep` | `for` + `max_steps`；Final 返回，模型/工具/调用错误失败；无事务/持久化 | `test_agent_loop.py` A–G |
| `agent-service/src/devpilot_agent_service/tools/base.py` | Tool 能力端口与模型可见定义 | Registry/Model → Tool 实现 | `Tool` Protocol、`ToolDefinition`、`JsonValue` | Tool 不是 DAO；定义复制为只读 mapping | `test_tool_registry.py` |
| `agent-service/src/devpilot_agent_service/tools/registry.py` | Tool 注册和统一执行边界 | AgentLoop → Tool | `register/get/definitions/execute` | duplicate/unknown/invalid args/tool exception/非 JSON 结果明确分类 | `test_tool_registry.py`、`test_agent_loop.py` |
| `agent-service/src/devpilot_agent_service/tools/echo.py` | 无副作用教学 Tool | Registry → 纯内存返回 | `EchoTool.execute` | 只接受一个字符串 text；无网络、文件、数据库状态 | Registry/Loop 测试 |
| `agent-service/tests/fakes/__init__.py` | 测试 Fake 包边界 | pytest import → 无 | 无业务类 | 仅测试状态 | pytest discovery |
| `agent-service/tests/fakes/fake_model.py` | 脚本化 Model Fake | AgentLoop 测试 → 预设响应/异常 | `FakeModel`、`RecordedModelCall` | 脚本耗尽明确失败；保存每轮不可变输入快照 | `test_agent_loop.py` |
| `agent-service/tests/test_message.py` | Message/响应不变量 | pytest → runtime/model types | 角色、Tool Result、Final/ToolCalls 用例 | 覆盖非法 role 和关联字段 | 8 个收集场景 |
| `agent-service/tests/test_tool_registry.py` | Registry 正常与失败路径 | pytest → Registry/EchoTool | 注册、执行、异常 Tool | duplicate、unknown、参数、Tool exception | 8 个收集场景 |
| `agent-service/tests/test_agent_loop.py` | 完整 Runtime 循环 | pytest → FakeModel/Loop/Registry | 无 Tool、一次/多轮 Tool、各 stop reason | 覆盖 A–G，证明不会无限循环 | 9 个测试 |
| `docs/agent/02-minimal-agent-runtime.md` | 第 2 章学习文档 | 开发者阅读 → 核心 Python 文件 | 架构、调用链、Java 类比 | 明确安全/数据/RPC 禁区 | 文档与实现人工核对 |
| `docs/changes/agent-02-minimal-runtime-file-map.md` | 本章变更报告 | Reviewer → 全部 Diff | 文件地图、导读、验证 | 不参与运行时状态 | `git diff --check` |

### [MOD]

| 文件 | 职责/所属模块 | 调用与关键变化 | 错误/状态/测试影响 |
| --- | --- | --- | --- |
| `.gitignore` | Git 配置 | 放行第 2 章文件地图 | 无运行时影响；`git status` 验证可见 |
| `README.md` | 仓库入口 | 从“只有边界”更新为已有 Python 单进程最小 Loop，并增加文档链接 | 不宣称真实 LLM/RPC/业务 Tool |
| `agent-service/README.md` | Python 工程入口 | 增加核心代码地图、当前能力和禁区 | 验证命令保持 pytest/Ruff |
| `agent-service/src/devpilot_agent_service/model/__init__.py` | Model 包说明 | 从未来占位更新为当前 Provider 无关边界 | 无 import 副作用 |
| `agent-service/src/devpilot_agent_service/runtime/__init__.py` | Runtime 包说明 | 标记 Message/Loop 已实现，checkpoint 未实现 | 无 import 副作用 |
| `agent-service/src/devpilot_agent_service/tools/__init__.py` | Tool 包说明 | 标记 Protocol/Registry/Echo 已实现 | 无 import 副作用 |
| `docs/architecture.md` | 全仓架构 | 记录第 2 章只改变 Python 单进程 Runtime，不改变 Java 模块图 | 继续禁止数据库/RPC 越界 |
| `docs/capability-coverage-and-roadmap.md` | 能力路线 | L0 从纯骨架演进到 FakeModel 最小 Loop | 明确仍不是 Agent L1 |

### [DEL]

无。

## 3. 完整调用链

### 无 Tool

```text
Python caller
→ AgentLoop.run(user_input)
→ Message.user
→ FakeModel / future Provider Model.generate
→ ModelResponse.FINAL
→ Message.assistant
→ RunResult(MODEL_FINAL)
```

### 有 Tool

```text
Python caller
→ AgentLoop
→ Model.generate(messages, ToolDefinitions)
→ ModelResponse.TOOL_CALLS
→ Message.assistant(tool_calls)
→ ToolRegistry.execute(name, arguments)
→ EchoTool / local test Tool
→ Message.tool_result(JSON, call_id, tool_name)
→ AgentLoop 下一 step
→ Model.generate（看到 Tool Result）
→ ModelResponse.FINAL
→ RunResult(MODEL_FINAL)
```

当前全部仍在 Python 单进程内，不存在 Java/Python RPC。没有任何链路访问 MySQL 或 `dp_*`。

## 4. 关键 Diff 导读

1. 第 1 章只有进程、模块、目录与 Proto 边界；第 2 章第一次加入可执行的多轮 Runtime 控制行为。
2. AgentLoop 依赖 `Model` Protocol，而不是具体 LLM SDK，使 FakeModel、未来 Provider Adapter 与核心 Loop 解耦。
3. `ModelResponse/ToolCall` 是结构化内部类型，不靠 `Thought:/Action:` 正则决定是否调用工具。
4. ToolRegistry 代替工具名 `if/else`，统一 duplicate、unknown、参数和执行异常边界。
5. `max_steps` 是不可省略的安全/资源硬上限；模型持续请求工具也只会执行有限轮。
6. 单元测试依赖 FakeModel，因为网络模型不稳定、需要 Secret、会消耗 Token，无法证明确定性控制流。
7. gRPC、真实 LLM、业务 Tool、Retry/Timeout/Cancel、Checkpoint、RAG、Memory、MCP、Multi-Agent 和人工确认均留给后续章节。

## 5. 推荐阅读顺序

1. `runtime/message.py`：先认识所有上下文共用的语义原子和 Tool Result 约束。
2. `model/types.py`：理解模型怎样明确表达 Final 或 ToolCall。
3. `model/base.py`：查看 Loop 依赖的最小 Provider 接口。
4. `tools/base.py`：理解 Tool 能力契约与模型可见定义。
5. `tools/registry.py`：理解工具查找、执行和失败分类为何集中。
6. `runtime/agent_loop.py`：带着前五个类型阅读完整控制循环、回填和停止线。
7. `runtime/errors.py`：核对五种停止语义如何映射到稳定异常。
8. `tests/fakes/fake_model.py`：理解脚本如何代替网络模型并保存输入快照。
9. `tests/test_agent_loop.py`：按 A–G 场景观察完整行为。
10. `test_message.py`、`test_tool_registry.py`、README 与配置：最后核对局部不变量和工程入口。

这个顺序先建立数据模型，再看端口和 Registry，最后读 orchestration，避免在 AgentLoop 中反向猜测每个类型的含义。

## 6. Java 学习类比

```text
Protocol             ↔ Java interface
frozen dataclass     ↔ Java record / immutable DTO（仅类比）
ToolRegistry         ↔ Map<String, Tool> + Registry service
AgentLoop            ↔ orchestration/application service + state machine
FakeModel            ↔ Mockito Stub / Fake implementation
```

## 7. 真实验证结果

| 命令/检查 | 状态 | 实际结果 |
| --- | --- | --- |
| `python -m pytest agent-service/tests` | PASS | 最终运行 26 passed in 0.11s |
| `python -m ruff check agent-service` | PASS | `All checks passed!`；Ruff 0.16.3 临时安装于系统临时目录，未写入项目/系统依赖 |
| `python -m compileall -q agent-service/src agent-service/tests` | PASS | 退出码 0 |
| 禁止依赖/数据库扫描 | PASS | Python 源码未发现 MySQL、`dp_*`、LLM SDK、LangGraph、gRPC、FastAPI 或 MCP 运行时导入 |
| `mvn -B -ntp clean verify` | PASS | 11/11 Reactor 模块成功；Boot 112 tests，0 failures/errors/skipped；BUILD SUCCESS，耗时 5:11 |
| `docker compose config --quiet` | PASS | 退出码 0，无输出 |
| `git diff --check` | PASS | 无空白错误；仅有既有 Windows LF/CRLF 提示 |
| Python 3.11 | PASS | 实际解释器为 Python 3.11.9 |
| 真实 LLM smoke test | NOT RUN | 本章没有 Provider Adapter，不需要也不读取 API Key |
| Java/Python RPC | NOT RUN | 本章明确禁止实现 |

## 8. 依赖与剩余风险

- `pyproject.toml` 的运行时 dependencies 仍为空；没有增加 Pydantic、OpenAI SDK、LangGraph 或数据库驱动。
- 参数 schema 当前是模型可见描述，具体业务参数由每个 Tool 显式校验；后续 Tool 数量增长时再评估统一 schema validator。
- Runtime 当前同步执行，慢 Tool 会阻塞当前调用线程；Timeout/Cancel 是后续可靠 Runtime/RPC 章节问题。
- 错误通过异常返回，不产生可持久化 Run 状态；AgentRun 投影仍属于未来 Java 边界。
- 当前轻量 Trace 只随成功 `RunResult` 返回；失败持久化、指标和分布式追踪尚未实现。

## 9. Git 安全

本轮未 reset、restore、checkout、clean、stash、commit、push 或修改 remote。第 1 章未提交变更保持在工作树中。
