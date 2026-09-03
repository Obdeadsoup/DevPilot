# P1-01 Runtime Persistence 实现与学习导读

本文记录 P1-01 完成时的设计与验收。后续状态机、快照 v2、取消和恢复能力见
[P1-02 Cancel / Resume](cancel-resume.md)。

本次在 `agent` 分支实现 Python RuntimeRun、Step 历史和版本化 JSON Checkpoint。
Java 继续持有用户可见 Run Metadata、身份、RBAC 和业务事务；SQLite 只包含 `runtime_*` 表。
没有修改 Java 业务代码或 protobuf 契约。

任务文档与当前代码有一处差异：仓库已经实现协作式 `CancelRun`。本次保留它，记录其既有取消结果，
没有新增取消 API、Resume、HITL、写 Tool 或其他框架。

## A. 更新文件地图

下面路径均相对于仓库根目录。

### 新增文件

| 文件 | 职责 | 被谁调用 / 为什么新增 |
|---|---|---|
| `agent-service/src/devpilot_agent_service/runtime/persistence.py` | Run/Step/Checkpoint 领域模型，Message 显式编码及 v1 解码 | Loop 和 Repository 共用；把执行事实与 Provider、数据库结构分离 |
| `agent-service/src/devpilot_agent_service/runtime/repository.py` | Repository Protocol、事务接口、稳定存储错误 | Loop 依赖；替换 PostgreSQL 时无需重写编排逻辑 |
| `agent-service/src/devpilot_agent_service/runtime/sqlite_repository.py` | 三张 Runtime 表、序号分配、状态转换、事务与查询 | 配置工厂装配；提供第一版磁盘存储 |
| `agent-service/src/devpilot_agent_service/runtime/redaction.py` | 结构化字段、嵌套 JSON 文本、凭据模式和已知密钥脱敏 | SQLite 所有 Payload 写入调用；阻止执行数据直接原样落库 |
| `agent-service/tests/conftest.py` | 每个测试独立的临时 SQLite fixture | 原有和新增 Runtime 测试使用；不产生共享开发数据库 |
| `agent-service/tests/test_runtime_repository.py` | CRUD、版本、并发、事务回滚、进程重启读回、安全验证 | pytest；验证存储保证而非仅验证内存对象 |
| `agent-service/tests/test_runtime_persistence.py` | Final/Tool/Failure/Guard/Cancel 的持久化验收 | pytest；证明编排语义与持久化事实一致 |
| `agent-service/tests/test_runtime_stream_persistence.py` | 真实 TCP gRPC unary、stream、断流后完成和重复 Run 拒绝 | pytest；验证独立 worker 的持久化生命周期 |
| `agent-service/docs/runtime-persistence.md` | 本学习材料与验收说明 | 从两份 README 进入；解释实现、边界和取舍 |

### 修改文件

| 文件 | 原职责 | 本次修改及与持久化的关系 |
|---|---|---|
| `agent-service/src/devpilot_agent_service/runtime/agent_loop.py` | Model/Tool 同步编排、预算、去重、事件与取消检查 | 注入 Repository，创建 Run/Step，原子保存边界及终态；结果增加 run_id 便于本地查询 |
| `agent-service/src/devpilot_agent_service/config.py` | 静态服务身份 | 增加数据库默认路径和 Repository 工厂；已知 Provider/内部 service key 只用于脱敏 |
| `agent-service/src/devpilot_agent_service/rpc/server.py` | Server 配置和 Model/Tool 装配 | 读取 AGENT_RUNTIME_DB_PATH；fake/DeepSeek 均注入 Repository |
| `agent-service/src/devpilot_agent_service/rpc/servicer.py` | gRPC 请求校验、Queue bridge、ActiveRunRegistry | Unary 已存在 Run 映射 ALREADY_EXISTS；解释断流与持久化 worker 生命周期分离 |
| `agent-service/examples/deepseek_tool_smoke.py` | 人工真实模型 smoke | 注入配置工厂创建的 Repository |
| `agent-service/tests/test_agent_loop.py` | 原有编排、guard、取消测试 | 注入临时数据库；原断言保持原有语义 |
| `agent-service/tests/test_remote_tools.py` | Remote Tool 定义和上下文传递 | Loop 使用临时 Repository，保留 RunContext/ToolCall 关联断言 |
| `agent-service/tests/test_rpc_server.py` | 配置和真实 Server bootstrap | 临时数据库、路径覆盖/非法配置测试 |
| `agent-service/tests/test_rpc_servicer.py` | unary、事件顺序、队列和取消测试 | 所有 Loop 使用临时 Repository，继续验证原有事件协议 |
| `agent-service/tests/cross_language_tool_smoke.py` | Java 启动独立 Python 验证反向 Tool 调用 | TemporaryDirectory 创建 Runtime DB，退出后清理 |
| `devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageAgentResilienceSmokeTest.java` | 跨进程恢复、熔断、取消、容量测试 | JUnit TempDir 给子进程提供 Runtime DB，避免固定 Run ID 污染下一次测试 |
| `devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageGrpcSmokeTest.java` | Java 调 Python unary/stream smoke | 每次使用新 Run ID；持久化 Server 上重复测试不触发已存在 Run 拒绝 |
| `.env.example` | 配置样例 | 增加 AGENT_RUNTIME_DB_PATH，说明进程工作目录和 Compose 差异 |
| `.gitignore` | 排除本地生成物 | 排除 .runtime、SQLite/DB 文件及 WAL/SHM/journal 边车 |
| `agent-service/Dockerfile` | Python 非 root 镜像 | 创建 devpilot 用户可写的 /app/data，并设置容器默认数据库路径 |
| `compose.yaml` | 本地全栈装配 | Agent 挂载独立 named volume，容器重建保留 Runtime 数据 |
| `agent-service/README.md` | Python 服务使用与边界 | 更新持久化、已有取消事实、配置、查询示例和学习材料入口 |
| `README.md` | 项目总览 | 增加 Runtime Persistence 能力与学习材料入口 |

### 推荐代码阅读顺序

1. [领域模型和显式状态](../src/devpilot_agent_service/runtime/persistence.py)：先理解三个不同层次的记录。
2. [Repository 接口](../src/devpilot_agent_service/runtime/repository.py)：先看 Loop 被允许做什么。
3. [SQLite 实现](../src/devpilot_agent_service/runtime/sqlite_repository.py)：看事务、状态转换和序号分配。
4. [脱敏](../src/devpilot_agent_service/runtime/redaction.py)：理解保存副本与执行输入的区别。
5. [AgentLoop](../src/devpilot_agent_service/runtime/agent_loop.py)：沿一个 Model→Tool→Model 路径阅读。
6. [组合根](../src/devpilot_agent_service/config.py) → [Server](../src/devpilot_agent_service/rpc/server.py)
   → [Servicer](../src/devpilot_agent_service/rpc/servicer.py)：理解数据库配置、Run ID 和 worker 的来源。
7. [Repository 测试](../tests/test_runtime_repository.py) → [Loop 验收](../tests/test_runtime_persistence.py)
   → [真实断流测试](../tests/test_runtime_stream_persistence.py)：用反例检验设计保证。

## B. 完整调用链

```text
Browser → Java Gateway → Java Core
  → 身份 / scope / RBAC → Java 业务 Run Metadata 提交
  → StartRun / StreamRun(request_id, run_id, user_input)
  → AgentRuntimeServicer：校验、注册 CancellationToken
  → AgentRuntimeApplication.start_run
  → AgentLoop.run
  → Repository.create_run：PENDING → RUNNING（短事务）
  → create MODEL_CALL Step + 更新模型轮次（提交）
  → RuntimeEvent.MODEL_STEP_STARTED → Model.generate
  → finish MODEL_CALL Step + Checkpoint（原子提交）
  → duplicate tool_call_id / max_tool_calls 整批预检
  → create TOOL_CALL Step + 累计调用次数（提交）
  → RuntimeEvent.TOOL_STARTED
  → ToolRegistry.execute → Remote Tool Adapter
  → JavaToolGatewayClient → Java Tool Gateway
  → service key 校验 → 从 run_id 恢复业务委托 → RBAC → 只读业务执行
  → 有界 Tool Result → Tool message → completed_tool_call_ids
  → finish TOOL_CALL Step + Checkpoint（原子提交）
  → RuntimeEvent.TOOL_COMPLETED
  → 下一轮 MODEL_CALL → Final Answer
  → finish MODEL_CALL Step + RuntimeRun SUCCEEDED + terminal Checkpoint（原子提交）
  → RunResult → Unary Response / RUN_SUCCEEDED Event
  → Java 条件更新终态 → Browser
```

如果第一次 Model 直接返回 Final，调用链在该次 MODEL_CALL 后直接结束，不创建 TOOL_CALL。

| 层 | 输入 → 输出 | 状态与异常行为 |
|---|---|---|
| Java 入口 | 用户输入和认证 → run_id/request_id | Java 做权威权限校验和业务投影，本次不改变其事务 |
| Servicer | protobuf 请求 → RunContext、token | 空字段 INVALID_ARGUMENT；活跃重复调用 ALREADY_EXISTS；内部异常仍按现有安全通道返回 |
| Application | input/context/token/hook → RunResult | 只委托同一 Loop，不复制状态机 |
| Run 创建 | run_id → RuntimeRun | 新建 PENDING→RUNNING；重复记录不覆盖、不隐式 Resume，不再执行 Model/Tool |
| Model Step 开始 | messages → RUNNING Step | Model 被调用之前，Step 和轮次已经提交；未持有数据库事务等待模型 |
| Model 调用 | Message、ToolDefinition → ModelResponse | ProviderError 保留现有 Runtime 分类；未知异常包装 ModelInvocationError；非法返回保持 InvalidModelResponseError |
| Model Step 完成 | 结构化 assistant message → SUCCEEDED Step 和 Checkpoint | Final 同事务将 Run 置 SUCCEEDED；Tool 请求保存完整调用批次后才进行预算/去重检查 |
| Tool guard | 当批 ToolCall IDs 和累计预算 → 放行或拒绝 | 同批重复、历史重复、超预算都在执行这一批的任何 Tool 前拒绝；Run FAILED，已成功的 Model Step 仍是 SUCCEEDED |
| ToolRegistry | name/arguments/context/call_id → JSON result | 未知 Tool、非法参数或执行错误保持原有抛异常语义；未改成“把异常喂给 LLM” |
| Java Tool Gateway | run_id/call_id/arguments 和内部服务身份 → 只读业务结果 | Python 不携带用户角色做权限决定；Java 重新解析委托、检查权限；认证/超时/业务失败按原有 Tool 错误链返回 |
| Tool Step 完成 | result → Tool message、completed ID、Step、Checkpoint | 每完成一个 Tool 就保存，支持保留部分批次进度；Tool 调用次数统计已开始的尝试，包含失败的那次 |
| 失败收尾 | 稳定 StopReason → FAILED Step/Run/Checkpoint | 仅当前仍 RUNNING 的 Step 标失败；guard 失败不篡改此前成功 Step；不保存异常正文或 cause |
| 显式取消 | 原有 token → RunCancelled | 记录 CANCELLED 和终态快照；已经提交的 Tool 结果保留；取消前尚未调用 Model 时允许 after_step=0 |
| 返回 Java | Final 或稳定失败类型 → Unary/Stream | 成功终态在数据库提交后才返回；流断开停止投递，worker 继续执行 |

SQLite 不可写时采用失败关闭：不会返回一个未经持久化确认的成功。Loop 尽力记录稳定的失败分类；
如果失败收尾也无法写入，只记录固定日志并保留原异常。不能声称磁盘故障时数据库一定能保存 FAILED。

Unary 对历史重复 Run 返回 `ALREADY_EXISTS`；Streaming worker 仍使用原协议的 `RUN_FAILED/INTERNAL` 通道，
避免向 Java 增加其尚不认识的 failure kind。两条路径均不重新执行旧 Run。

## C. Checkpoint 生命周期

### 生成位置

| 边界 | Run 状态 | next_action | 保存原因 |
|---|---|---|---|
| Model 返回 ToolCall | RUNNING | TOOLS | 保留完整 assistant 批次，未来能知道哪些工具尚未执行 |
| 一个 Tool 成功返回 | RUNNING | TOOLS 或 MODEL | 保存结果和 completed IDs；批次中断不会丢掉此前完成项 |
| Model 返回 Final | SUCCEEDED | TERMINAL | 最终回答、Step 终态和 Run 终态原子一致 |
| Model/Tool/guard 失败 | FAILED | TERMINAL | 保存最后可诊断的状态；当前动作失败不会被误记成成功 |
| 既有显式取消被观察到 | CANCELLED | TERMINAL | 与断流区分；保留已经完成的执行事实 |

Run 创建时不额外生成 Checkpoint。正在调用 Model/Tool 时，Run 的当前进度可以领先于最新 Checkpoint：
前者说明已开始执行，后者只表示已经提交的边界。成功的“单次 Final”恰有 1 个快照，
“Model→Tool→Model Final”恰有 3 个快照。guard/取消收尾可能在同一个 after_step 后新增终态快照。

### state_json 的内容

```json
{
  "version": 1,
  "messages": [
    {"role": "user", "content": "hello", "tool_calls": [], "tool_call_id": null, "tool_name": null}
  ],
  "current_step": 1,
  "tool_call_count": 0,
  "completed_tool_call_ids": [],
  "status": "RUNNING",
  "next_action": "MODEL",
  "max_steps": 8,
  "max_tool_calls": 16,
  "request_id": "request-id",
  "redacted": false
}
```

上面只展示字段格式。实际 messages 包括 system/history/user/assistant/tool；每个 assistant ToolCall
显式保存 call_id、name、arguments，每个 Tool result 保留 tool_call_id 和 tool_name。
保存 max_steps/max_tool_calls 避免未来恢复时重置预算；request_id 只用于关联，不是授权凭证。
运行时 Trace 是返回调用方的控制流视图，不保存为 Python 对象；持久化 Step 提供独立执行历史。

### 未来 Resume 如何使用

读取 `get_latest_checkpoint(run_id)`，校验 `state_version` 和 JSON version，再通过 `.state` 重建
Message/ToolCall。`next_action=TOOLS` 时，从最近 assistant 批次排除 completed IDs，识别剩余工具；
`MODEL` 时结合 current_step 和原预算确定下一轮，不能重新从第 1 轮计数。

这只是恢复基础。真正 Resume 还要定义运行所有权、崩溃中动作的处理、终态是否允许重试、重新授权、
Provider/ToolRegistry 配置兼容以及凭据重新建立。不能因为快照存在就自动执行。
`redacted=true` 表示恢复材料已缺失部分敏感内容，不能盲目把 `[REDACTED]` 当原始参数重放。
本次不提供任何 Resume 执行入口。

Tool 已执行但进程在 Checkpoint 提交前崩溃时，仍然存在不确定窗口。completed IDs 不等于业务侧 exactly-once；
未来写 Tool 必须由 Java 业务事务和幂等键共同保证副作用安全。

## D. 关键 Diff 导读

1. **Repository 抽象**：`AgentLoop(..., repository=...)` 是显式依赖；接口包含 Run/Step CRUD、
   checkpoint 查询和 `transaction()`。Loop 中没有 SQL、文件路径或 SQLite 连接。
   `RunAlreadyExists` 与 `RuntimeStateConflict` 使重复 Run 和非法状态变化成为可测试的契约。

2. **Runtime 领域模型**：RunStatus 包含 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED；
   StepStatus 是 RUNNING/SUCCEEDED/FAILED。`current_step` 是模型轮次，`step_no` 则遍历所有 Model/Tool 动作。
   例如一轮 Model 加两个 Tool，对应 current_step=1、step_no=1/2/3。

3. **SQLite 事务与编号**：`BEGIN IMMEDIATE` 先获得写锁，再执行 `MAX(step_no)+1` 或
   `MAX(checkpoint_no)+1`。UNIQUE 约束兜底；同进程不同 worker、不同 Repository 实例共享此保证。
   方法独立调用会自行开短事务，Loop 的外层事务把多个方法合为一个原子边界。
   每个顶层操作结束关闭连接；使用线程局部连接，不把同一 sqlite3.Connection 跨线程共享。

4. **Step 开始先提交**：创建 RUNNING Step 后才调用 Model/Tool，执行中途崩溃也留下已开始的事实。
   数据库锁不跨 LLM 网络等待、Java Tool RPC 或 Queue 背压。

5. **Step 结果与 Checkpoint 一起提交**：核心结构是
   `with repository.transaction(): finish_step(...); checkpoint(...)`。
   checkpoint helper 在同一事务更新 Run 进度并保存状态。Final 的 Step、Run SUCCEEDED、最终快照一起提交，
   失败注入测试证明写快照失败不会留下半个成功终态。

6. **部分 Tool 批次**：每个 Tool 成功后立即追加消息和 completed ID，而不是等整批执行结束才保存。
   `next_action` 区分仍有 Tool 等待和可以进入下一轮 Model。原有重复 ID 与工具预算仍按整批预检。

7. **失败处理**：ModelProvider 异常仍包装为原有 ModelInvocationError；ToolRegistry 的错误语义不变。
   当前活跃 Step 标 FAILED，Run 标 FAILED，快照记录终态；保存的是稳定 code 和固定说明。
   原始异常仍抛给现有 RPC 安全转换层，数据库故障也不会被静默当作成功。

8. **脱敏边界**：在 Repository 写入前递归处理安全副本，包括 JSON 编码的 Tool message.content。
   识别 token/secret/authorization/service key 等字段和常见文本模式，并移除配置中真实已知密钥。
   不改变模型正在使用的内存消息。未知无标签凭据不可能全部靠模式识别，输入和 Tool 输出仍需保持最小化；
   此机制不是内容加密，也不是任意敏感数据检测器。

9. **配置和容器存储**：本地默认 `.runtime/agent-runtime.sqlite3`；允许环境变量覆盖。
   Compose 使用固定容器路径与专用数据卷；Dockerfile 创建非 root 用户可写目录，避免持久化一接入就启动失败。

10. **测试**：原有测试全部使用临时 Repository；新增测试检查真实数据库内容、跨进程读回和并发约束，
    真实 TCP gRPC 测试中调用 transport `cancel()` 后放行阻塞 Model，验证最终 Run 和 Checkpoint 仍为 SUCCEEDED。
    Java 跨进程 smoke 使用新 Run ID/临时 DB，保持重复执行测试的可复现性。

## E. 数据库 Schema 说明

### runtime_runs

| 字段 | 含义 |
|---|---|
| run_id | TEXT 主键，通常来自 Java；本地无 RunContext 时生成 UUID 并通过 RunResult 返回 |
| status | 受 CHECK 约束的五种 Runtime 状态 |
| current_step | 当前模型轮次，非负；不与每个动作的 step_no 混用 |
| tool_call_count | 已开始的 Tool 尝试次数，非负 |
| created_at / updated_at | UTC ISO-8601 创建和最近更新时刻 |
| started_at / finished_at | 开始和终止时刻，未发生时为空 |
| failure_code / failure_message | 失败分类和安全说明；非 FAILED 状态不保留失败字段 |

Repository 只允许 PENDING→RUNNING、RUNNING→RUNNING/终态；禁止终态重开和计数回退。
run_id 主键支持点查并防止重复创建，不需要同字段再建索引。

### runtime_steps

| 字段 | 含义 |
|---|---|
| step_id | UUID TEXT 主键，供 finish/fail 精确更新 |
| run_id | 指向 runtime_runs 的外键 |
| step_no | 同 Run 内从 1 递增的动作编号 |
| step_type | CHECK：MODEL_CALL 或 TOOL_CALL |
| status | CHECK：RUNNING、SUCCEEDED、FAILED |
| started_at / finished_at | 动作开始和完成时刻；开始时 finished_at 为空 |
| input | 脱敏 JSON；Model 为消息输入，Tool 为 call_id/name/arguments |
| output | 脱敏 JSON；Model 为 assistant 消息，Tool 为业务结果 |
| error | 稳定错误 JSON；成功时为空/JSON null |

`UNIQUE(run_id, step_no)` 既保证动作编号不重复，又生成支持按 Run 有序读取的复合索引。
`finish_step/fail_step` 使用 `WHERE status='RUNNING'` 条件更新，防止覆盖已完成的 Step。

### runtime_checkpoints

| 字段 | 含义 |
|---|---|
| checkpoint_id | UUID TEXT 主键 |
| run_id | 指向 runtime_runs 的外键 |
| checkpoint_no | 同 Run 内从 1 递增的快照编号 |
| after_step | 快照对应的最新动作序号；未创建动作前取消可以为 0 |
| state_version | 显式状态版本，当前为 1 |
| state_json | 经过脱敏的 RuntimeCheckpointState JSON |
| created_at | UTC ISO-8601 保存时刻 |

`UNIQUE(run_id, checkpoint_no)` 支持
`WHERE run_id=? ORDER BY checkpoint_no DESC LIMIT 1`，无需额外重复索引。
after_step 不是独立外键，因为合法的“尚无动作”快照需要 0；Repository 校验它等于该 Run 最新 step_no，
并校验快照的 status/current_step/tool_call_count 与 Run 记录一致。
所有连接启用 foreign_keys；数据库使用 WAL，写入以短事务串行化，锁等待超时为 10 秒。
当前连读取也经过同一简洁的事务通道，适用于现有小规模 Runtime，不承诺高吞吐或分布式协调。

## F. 设计取舍

1. **为什么不用 Java 保存所有 Runtime State？** Java 已经负责用户可见业务投影；模型上下文、
   部分 Tool 批次和检查点属于 Python 执行引擎。把它们都搬到 Java 会让业务服务随每个 Runtime 内部变化一起演进。

2. **为什么 Python 不直接操作 dp_*？** 业务授权、事务和数据完整性必须只有一个权威边界。
   Python 通过 Tool Gateway 请求业务能力，避免绕过 Java 或复制 RBAC。

3. **为什么先用 SQLite？** 标准库即可使用，支持事务、约束和磁盘恢复，开发和测试无需另部署数据库。
   代价是单写者、有限并发和本地文件部署范围，适合当前阶段，不作为多副本方案。

4. **为什么 Repository 抽象重要？** Loop 只表达执行事实和原子边界；连接管理、SQL、编号并发及脱敏
   由实现提供。替换存储时需要满足同一契约，无须让编排代码感知 PostgreSQL 语法。

5. **为什么 Step 和 Checkpoint 分开？** Step 解释“发生了哪次调用、成功还是失败”，
   Checkpoint 解释“在这个边界接下来可以做什么”。同一个 Step 后可能有正常和失败/取消两个快照，二者不是一对一。

6. **为什么不能 pickle 整个 Agent？** Agent 含模型客户端、连接、token、回调及未来可能的凭据，
   对象布局又依赖 Python 代码版本。显式 JSON 能检查内容、脱敏、版本校验，并只恢复需要的执行数据。

7. **为什么 Stream 断开不能代表 Run Cancel？** Stream 是观察通道；客户端超时或网络断开不能证明
   Model/Tool 没有继续执行。worker 独立落库，显式 CancelRun 的 token 才影响执行状态。

8. **为什么 completed_tool_call_ids 重要？** 它把原本只存在内存中的完成事实带过进程生命周期，
   使未来能够识别部分批次中的已完成调用。它不能消除“副作用已提交、快照未提交”的窗口，
   所以未来写 Tool 的最终幂等仍应由 Java 业务边界保证。

### 跨服务同步风险与当前限制

Java 和 Python 之间没有分布式事务。Java 超时后可能记 FAILED，而 Python 继续执行并最终记 SUCCEEDED；
Java 提前提交 CANCELLED 与 Python 稍后观察取消也有时间差。Python 已提交终态后若终态事件丢失，
Java 仍可能不知道最终结果。本次保留双方职责和原有事件协议，没有添加 Runtime Outbox 或消息队列。

进程被强制终止时可以留下 RUNNING Run/Step，Checkpoint 保留最近已提交边界；本次不扫描接管或自动重试。
Python ActiveRunRegistry 的取消记录仍在内存中，没有借此次任务改造为分布式取消状态。
原有 worker/队列机制保持不变，本次也没有新增全局 worker 容量限制。

后续在 P1-02 中应明确状态查询/对账、恢复所有权、历史终态政策和取消恢复规则，不能只重发 StartRun。
在引入写 Tool 前必须处理 Java 幂等和重新授权；这属于后续范围。

## G. 后续任务建议

- P1-02 Cancel / Resume
- P1-03 Write Tool + Proposal
- P1-04 HITL Approval

## 验证与 Git 交付

改动前 Python 基线为 102 项通过。完成后执行全量 Python 测试、Ruff、Java Agent 及依赖模块测试，
并显式启用三个跨语言 smoke（unary/stream、resilience/cancel、Python→Java Tool）。
所有自动化模型调用均使用 Fake/Stub，没有访问真实 LLM 或消耗 API Token。

2026-09-03 最终验收结果：

| 验证 | 结果 |
|---|---|
| Python 全量测试 | 131 passed |
| Ruff | All checks passed |
| Java Agent 及依赖模块 | 301 tests，0 failures，0 errors，0 skipped |
| 其中 Java Agent 模块 | 80 tests，包含上述 3 个实际执行的跨语言 smoke |
| git diff --check | 通过 |
| 学习材料内部文件链接 | 全部可解析 |
| Compose 配置 | 使用占位环境校验通过；Docker 引擎不可用，未运行容器 |

```powershell
.\.venv\Scripts\python.exe -m pytest agent-service/tests
.\.venv\Scripts\python.exe -m ruff check agent-service
mvn -B -pl devpilot-agent -am test
git diff --check
git status --short
```

运行 Java 跨进程 smoke 需显式设置 DEVPILOT_AGENT_CROSS_LANGUAGE_SMOKE、
DEVPILOT_AGENT_RESILIENCE_SMOKE、DEVPILOT_AGENT_TOOL_CROSS_LANGUAGE_SMOKE 为 true，
指定 DEVPILOT_AGENT_PYTHON，并给第一个 smoke 启动独立 fake Server。测试 Server 应使用临时数据库路径；
Resilience 和 Tool smoke 自行启动子进程并管理临时数据库。仅测试 loopback 时绕过本机 HTTP/gRPC 代理。

Compose 使用占位测试环境执行 `docker compose --env-file .env.example --profile full config --quiet`
通过。本机 Docker Desktop Linux Engine 不可连接，未执行镜像构建/容器启动，不把配置校验称为容器实测。

本次全部改动保留在 `agent` 工作区供审阅；没有自动 commit、push 或 merge 到 main。
完成时 `git status --short --untracked-files=all` 输出如下（18 个修改文件、9 个新增文件）：

```text
 M .env.example
 M .gitignore
 M README.md
 M agent-service/Dockerfile
 M agent-service/README.md
 M agent-service/examples/deepseek_tool_smoke.py
 M agent-service/src/devpilot_agent_service/config.py
 M agent-service/src/devpilot_agent_service/rpc/server.py
 M agent-service/src/devpilot_agent_service/rpc/servicer.py
 M agent-service/src/devpilot_agent_service/runtime/agent_loop.py
 M agent-service/tests/cross_language_tool_smoke.py
 M agent-service/tests/test_agent_loop.py
 M agent-service/tests/test_remote_tools.py
 M agent-service/tests/test_rpc_server.py
 M agent-service/tests/test_rpc_servicer.py
 M compose.yaml
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageAgentResilienceSmokeTest.java
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageGrpcSmokeTest.java
?? agent-service/docs/runtime-persistence.md
?? agent-service/src/devpilot_agent_service/runtime/persistence.py
?? agent-service/src/devpilot_agent_service/runtime/redaction.py
?? agent-service/src/devpilot_agent_service/runtime/repository.py
?? agent-service/src/devpilot_agent_service/runtime/sqlite_repository.py
?? agent-service/tests/conftest.py
?? agent-service/tests/test_runtime_persistence.py
?? agent-service/tests/test_runtime_repository.py
?? agent-service/tests/test_runtime_stream_persistence.py
```

推荐提交消息：

```text
feat(agent): add runtime persistence and checkpoint foundation
```
