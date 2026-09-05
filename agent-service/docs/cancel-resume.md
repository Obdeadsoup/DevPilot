# P1-02 持久取消、Checkpoint Resume 与启动恢复

本次继续在 `agent` 分支、已有 P1-01 和协作式 CancelRun 上实现。新增的是取消意图持久化、CAS 状态转换、
显式 Checkpoint v2 恢复、ResumeRun gRPC 和单实例启动收敛；没有新增写 Tool、HITL、MCP 或外部编排框架。
原 StartRun / StreamRun / CancelRun 的字段和事件类型保持兼容。

## A. 更新文件地图与阅读顺序

下列列表以本轮开始时的 P1-01 工作区为基线。Git 尚包含上一轮未提交的 P1-01，因此 `git status` 的完整列表更长。
路径均相对于仓库根目录。

### 新增文件

| 文件 | 职责、调用者与新增原因 |
|---|---|
| `agent-service/src/devpilot_agent_service/runtime/schema.py` | Repository 初始化调用；维护 SQLite v2，并原地保留 P1-01 三表和历史 JSON，避免删除数据库重建 |
| `agent-service/src/devpilot_agent_service/runtime/recovery.py` | Loop 调用；白名单故障分类、恢复资格、显式控制状态/协议/计数校验 |
| `agent-service/tests/test_cancel_resume.py` | pytest；取消状态、CAS 竞态、暂时性故障、部分批次恢复、预算、非法快照和并发 Resume |
| `agent-service/tests/test_runtime_recovery.py` | pytest；真实 Server bootstrap、v1 数据迁移、Finalization 和动作提交中断注入 |
| `agent-service/tests/test_cancel_resume_rpc.py` | pytest；真实 TCP Resume、阻塞 I/O 中取消、持久写失败、断流和满事件队列取消 |
| `agent-service/tests/cross_language_resume_server.py` | Java smoke 启动的独立 Python 测试 Server；首个 Model 暂时失败、恢复后校验原输入 |
| `devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageAgentResumeSmokeTest.java` | JUnit；Java 生成 Stub 调 Python ResumeRun，验证第 2 步、原上下文和兼容 Cancel 响应 |
| `agent-service/docs/cancel-resume.md` | 本学习材料；汇总调用链、故障推演、Diff、限制和验收 |

### 修改文件

| 文件 | 原职责 → 本次变化 |
|---|---|
| `agent-service/src/devpilot_agent_service/runtime/persistence.py` | 执行事实/v1 状态 → 增加 CANCEL_REQUESTED、request_id/retryable/version、CancelDecision、显式 pending_tool_calls/final_answer 和 v2 |
| `agent-service/src/devpilot_agent_service/runtime/repository.py` | 持久化接口 → 增加 CAS、取消意图和启动收敛接口；Loop 不依赖 SQL |
| `agent-service/src/devpilot_agent_service/runtime/sqlite_repository.py` | SQLite CRUD → 条件状态更新、持久关联身份、重复取消、失败可恢复标记、旧 worker 收敛 |
| `agent-service/src/devpilot_agent_service/runtime/agent_loop.py` | 顺序有界循环 → prepare/execute 分离，按 MODEL/TOOLS/FINALIZE 分派；取消安全点与显式 Resume 共用执行器 |
| `agent-service/src/devpilot_agent_service/runtime/cancellation.py` | 线程内 Event/活跃注册表 → 暴露 is_cancelled；Event 用于及时通知，持久 Run 用于权威判定 |
| `agent-service/src/devpilot_agent_service/runtime/errors.py` | 原稳定异常 → ToolExecutionError 增加 retryable，增加安全的 ResumeRejected |
| `agent-service/src/devpilot_agent_service/tools/registry.py` | 工具执行/错误边界 → 保留 Tool 错误语义，同时向 Runtime 传递明确的暂时性失败标记 |
| `agent-service/src/devpilot_agent_service/rpc/tool_gateway_client.py` | Java Tool RPC 适配 → 只有 deadline/unavailable/circuit-open 标记为可重试，权限/协议/业务错误不重试 |
| `agent-service/src/devpilot_agent_service/rpc/application.py` | Loop 门面 → 增加准备、执行、取消持久化、恢复准备和启动收敛委托 |
| `agent-service/src/devpilot_agent_service/rpc/servicer.py` | gRPC/Queue bridge → RUN_STARTED 前准备持久状态；Cancel 先落库后 signal；增加 ResumeRun 并复用 streaming worker |
| `agent-service/src/devpilot_agent_service/rpc/server.py` | Server bootstrap → 绑定端口后、接收请求前进行单实例中断收敛 |
| `contracts/agent/v1/agent_runtime.proto` | 共享契约 → 追加 ResumeRun 和 CancelRunResponse.runtime_status，保留现有字段号 |
| `agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2.py` | 生成消息定义 → 由共享 proto 重新生成 |
| `agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2_grpc.py` | 生成服务定义 → 由生成脚本追加 ResumeRun Stub/handler，非手工业务实现 |
| `agent-service/tests/test_generated_grpc_contract.py` | Python 契约检查 → 验证 Resume 是 Server Streaming，旧 Cancel 字段号不变 |
| `agent-service/tests/test_runtime_persistence.py` | P1-01 持久化验收 → 加入初始/动作开始/Finalize 边界断言；动作成功后 Finalize 失败不篡改已成功 Step |
| `agent-service/tests/test_runtime_stream_persistence.py` | 断流验收 → 观察 execute_prepared 返回时刻，继续验证断流后完成 |
| `agent-service/README.md` | 服务操作指南 → 更新状态、迁移、恢复示例、限制和本文入口 |
| `contracts/agent/v1/README.md` | 契约说明 → 修正已有 Cancel 说明，解释新增 Resume 和安全拒绝 |
| `README.md` | 项目总览 → 更新 Runtime 恢复能力及入口范围 |
| `agent-service/docs/runtime-persistence.md` | P1-01 历史学习材料 → 标明其时间点并链接本文，保留上一轮验收记录 |

推荐阅读顺序：

1. [状态和 Checkpoint](../src/devpilot_agent_service/runtime/persistence.py)
2. [Repository 接口](../src/devpilot_agent_service/runtime/repository.py)
3. [Schema 迁移](../src/devpilot_agent_service/runtime/schema.py) → [SQLite CAS](../src/devpilot_agent_service/runtime/sqlite_repository.py)
4. [恢复校验与故障分类](../src/devpilot_agent_service/runtime/recovery.py)
5. [Loop 的 prepare_resume / execute_prepared](../src/devpilot_agent_service/runtime/agent_loop.py)
6. [gRPC Servicer](../src/devpilot_agent_service/rpc/servicer.py) → [Server 启动](../src/devpilot_agent_service/rpc/server.py)
7. [取消/恢复测试](../tests/test_cancel_resume.py) → [中断注入](../tests/test_runtime_recovery.py) → [真实 RPC](../tests/test_cancel_resume_rpc.py)

## B. Cancel 完整调用链

```text
Browser / Java 原有取消入口
→ CancelRun(run_id, 原 request_id)
→ Python Servicer 校验字段
→ Application.request_cancel
→ Repository 校验持久 request_id，读取当前 status/version
→ CAS PENDING→CANCELLED 或 RUNNING→CANCEL_REQUESTED，提交事务
→ ActiveRunRegistry 通知当前 CancellationToken/Event
→ 返回 accepted/status/runtime_status
→ AgentLoop safe_point 同时检查 Event 和持久状态
→ 停止后续 Model/Tool
→ 活跃未完成 Step 结束，Run→CANCELLED，Checkpoint→CANCELLED
→ 原 RUN_CANCELLED 事件
```

| 当前状态 | Cancel 行为 |
|---|---|
| PENDING | 直接 CANCELLED，accepted=true；尚无 worker 时不虚构执行事件 |
| RUNNING | 持久 CANCEL_REQUESTED，accepted=true，然后通知 token |
| CANCEL_REQUESTED | accepted=true，返回当前状态；重复请求不重复更新 version |
| SUCCEEDED / FAILED / CANCELLED | accepted=false、ALREADY_TERMINAL，不覆盖终态 |
| 不存在 / request_id 不匹配 | NOT_FOUND，不通知任何 worker |
| 取消意图写入失败 | gRPC INTERNAL；不提前 signal，不声称接受了持久取消 |

Run 的 request_id 是关联校验，不替代 Java 用户身份和 RBAC。所有步骤只操作 runtime_*。

### 竞态和终态保护

SQLite 使用带 status/version 条件的 UPDATE。Cancel 和成功收尾在同一数据库上竞争：

```sql
UPDATE runtime_runs
SET status = ?, version = version + 1, ...
WHERE run_id = ? AND status = ? AND version = ?;
```

Cancel 先提交时，Run 已是 CANCEL_REQUESTED，后续动作记录只能保留这个状态，不能写回 RUNNING；
Finalize 无法提交 SUCCEEDED。成功先提交时，迟到的 Cancel 返回 ALREADY_TERMINAL。
这不是无锁的“先读再无条件写”；读取和条件更新处于短事务内，并检查受影响行数。

token 在准备执行前注册，worker 真正退出时释放；历史终态查询来自数据库，不依赖内存 tombstone。
RUN_STARTED 在持久状态准备完成后发出。普通事件队列满时，已取消的 token 允许跳过普通事件投递，
使 worker 能继续到取消安全点；终态仍用现有流协议投递。

检查点包括进入循环、动作开始事务内、事件回调后实际 I/O 前，以及动作返回并原子记账后。
Provider/Tool 正阻塞时不强制打断线程：Cancel 先持久化，当前 I/O 返回后处理结果并阻止下一动作。
已经成功的 Tool Result 和 completed ID 保留；取消不是回滚。

原 Java 取消流程收到 ACCEPTED 后可能已经将用户可见投影标记 CANCELLED，而 Python 仍在等待阻塞调用返回。
这是业务取消已接受与执行停止已完成的不同时间点，runtime_status 用于区分它们。

## C. Resume 完整调用链

```text
ResumeRun(run_id, 原 request_id)                 [没有 user_input]
→ 注册本次执行的 CancellationToken
→ load RuntimeRun
→ 校验 FAILED + retryable + 白名单 failure_code
→ load latest Checkpoint
→ 校验 v2、显式控制字段、messages/Tool 关联、计数、预算、redacted
→ CAS FAILED→RUNNING（status/version）
→ 保存恢复后的 RUNNING 控制快照，同事务提交
→ 交给与 StreamRun 相同的 worker / execute_prepared
→ 按 next_action 分派
    MODEL    → 使用已保存 messages 调下一次 Model
    TOOLS    → 直接执行显式 pending 中未完成的调用
    FINALIZE → 使用已保存 final_answer 完成终态，不再调用 Model
→ 后续动作和终态继续持久化
```

### 显式 v2 控制状态

| 字段 | 恢复作用 |
|---|---|
| messages | 保存 system/history/user/assistant/tool 的完整协议关系；不重新追加原用户请求 |
| current_step | 已开始的模型尝试次数；恢复不会归零 |
| tool_call_count | 已开始的 Tool 尝试次数；失败/中断后再次尝试仍消耗预算 |
| completed_tool_call_ids | 已知成功调用；恢复时跳过这些 ID，且校验有相应 Tool Result |
| pending_tool_calls | 显式 call_id/name/arguments；不能靠重新分析 messages 猜下一动作 |
| next_action | MODEL / TOOLS / FINALIZE / TERMINAL；前三种才有可执行恢复位置 |
| final_answer | FINALIZE 的已确定结果，防止为取得答案而重新调用模型 |
| max_steps / max_tool_calls | 原执行预算，后续进程配置变化不会重置它们 |
| request_id / status / redacted | 关联一致性、历史控制状态，以及是否缺失敏感信息 |

开始 Run 时保存初始状态；每次 Model/Tool 开始前也在创建 Step 的同一事务保存控制状态。
因此在执行中崩溃时，预算和待执行阶段仍有明确记录。采取保守计费：即使崩溃发生在实际 I/O 前，
已提交的动作尝试也占用一次预算。恢复创建新的 Step，不重开或删除原失败 Step。

例如：Model 产生两个 Tool，one 已成功，two 尚未执行时崩溃。
快照显式保存 `pending=[two]`、`completed=[one]`、`current_step=1`、`tool_call_count=1`。
Resume 先执行 two，然后调用第 2 次 Model；one 不再执行，也不让 Model 重新生成整个批次。

Model 返回 Final 时，先原子保存成功 Model Step 和 FINALIZE 快照，再提交 Run 的成功终态。
两者之间崩溃可以仅恢复 Finalize；这是 v2 相比“所有完成都直接 TERMINAL”的控制状态扩展。

### 明确拒绝的情况

| 原因 | 稳定错误 |
|---|---|
| Run 不存在或关联不匹配 | RUN_NOT_FOUND / gRPC NOT_FOUND |
| 非 FAILED、不可重试、非白名单故障 | RUN_NOT_RETRYABLE |
| 无快照 | CHECKPOINT_NOT_FOUND |
| JSON 损坏、字段/计数/Tool 协议不一致 | INVALID_CHECKPOINT |
| v1 或未知版本 | UNSUPPORTED_STATE_VERSION |
| 存在脱敏导致的信息缺失 | REDACTED_CHECKPOINT |
| 原预算已不足 | MAX_STEPS_EXCEEDED / MAX_TOOL_CALLS_EXCEEDED |
| 同一进程已有活跃 worker | gRPC ALREADY_EXISTS |

除 NOT_FOUND 和活跃冲突外，恢复准备阶段的拒绝使用 FAILED_PRECONDITION。
验证失败不改变原 Run，不创建 Model/Tool，不回退到重新执行 User Request。
FAILED + retryable 只是候选资格，不代表可以跳过预算、版本和快照校验。

暂时性 Model 故障只包括 timeout/unavailable/rate-limit；暂时性 Tool 故障只包括 Java Gateway
deadline/unavailable/circuit-open。权限、认证、协议、未知异常和预算 guard 不会自动获得可恢复资格。
Runtime 数据库保存更细的 TEMPORARY_MODEL_ERROR/TEMPORARY_TOOL_ERROR；发送旧 Java 客户端时仍使用原有
MODEL_ERROR/TOOL_ERROR 分类，不破坏既有失败枚举。

### 流、Java 边界和当前使用范围

ResumeRun 是新增的内部 Runtime gRPC API，本次没有新增浏览器按钮或 Java REST Resume 编排。
每次流从 RUN_STARTED、sequence=1 开始，只描述本次执行，后续事件的 step 保留累计模型步号。
没有跨调用历史 replay 或全局事件序列；调用方必须按本次流消费，不能直接合并两次调用的事件 ID。
Resume 流断开仍不自动取消执行。

Java 仍维护权威业务投影，Tool Gateway 要求业务 Run 处于允许执行的状态。
如果 Java 已将 Run 标 FAILED，直接恢复 Python 并不自动把 Java 投影重新激活，真实 Remote Tool
可能被 Java 以 RUN_NOT_ACTIVE 拒绝。面向用户的恢复流程仍需由 Java 在授权后协调业务状态、调用 ResumeRun
并处理后续事件；本次没有通过 Python 直写 dp_* 或绕过 Java gate 来掩盖这一跨服务边界。
本轮真实 Java→Python Resume smoke 验证内部契约和执行恢复，不冒充已有完整浏览器恢复链路。

## D. Crash 场景推演

| 场景 | 已提交事实、重启行为和剩余限制 |
|---|---|
| 1. Model 调用前 Crash | Run、开始 Step、MODEL 控制快照和尝试计数已提交。启动标 FAILED/RUNTIME_INTERRUPTED，旧 Step 标失败；显式 Resume 从保存消息继续并消耗下一次预算。若只保存了初始快照，则从该初始边界开始 |
| 2. Model 已返回但尚未持久化 Crash | 最近快照仍是 MODEL。内存输出丢失，恢复需要重新调用 Model；可能再次产生模型费用，也可能获得不同输出，不能声称复用了未提交结果 |
| 3. Tool 尚未执行 Crash | 如果模型批次已提交，快照是 TOOLS 且有显式 pending；恢复直接执行待执行工具，不再次调用 Model。若 Tool 开始记录已提交，该尝试已经计入预算 |
| 4. Tool 成功但 Checkpoint 未保存 Crash | Java 可能已经完成调用，Python 仍只知道 pending。当前 read-only Tool 的显式恢复可能再次调用同一 ID；不能保证业务副作用恰好一次。未来必须用稳定 ID 与 Java 业务幂等 |
| 5. Step 已写入但 Checkpoint 未保存 Crash | 同一完成事务未提交时，Step 更新和快照一起回滚，启动仍看到原 RUNNING Step 并标为中断；不存在由本完成路径提交的半个成功边界。若完成事务已经提交，二者同时可见 |
| 6. Cancel 到达时 Model 阻塞 | 意图先写 CANCEL_REQUESTED 并立即确认；不强杀 I/O。返回后保存可记录的执行事实，在安全点结束 CANCELLED，不进入 Tool/下一轮模型 |
| 7. Cancel 与 Final Answer 同时发生 | CAS 决定赢家。Cancel 先提交则最终 CANCELLED；成功先提交则 Cancel 返回 ALREADY_TERMINAL。模型结果产生的时间不等于 Run 成功提交的时间 |

### 启动 reconciliation

`create_server` 在绑定成功后、开始接收请求前调用 Application→Repository：

```text
旧 RUNNING → FAILED / RUNTIME_INTERRUPTED / retryable=true
旧 PENDING → FAILED / RUNTIME_INTERRUPTED / retryable=true
旧 CANCEL_REQUESTED → CANCELLED / retryable=false
旧 RUNNING Step → FAILED，记录 RUNTIME_INTERRUPTED 或 CANCELLED
SUCCEEDED / FAILED / CANCELLED → 保持不变
```

收敛操作在一个 SQLite 事务中执行且幂等，不自动调用 Resume。
Checkpoint 保留最后提交的执行边界，可能仍携带之前的 RUNNING 状态；当前权威控制状态来自 RuntimeRun。
Resume 校验并接管后才保存新的 RUNNING 快照。取消收敛后的 Run 是 CANCELLED，即使历史快照是 RUNNING 也不能恢复。
损坏或缺失快照不会被“修复”为新用户请求；显式恢复时返回明确错误。

### 数据库迁移

SQLite `user_version=2`，runtime_runs 增加 request_id/retryable/version，status CHECK 增加 CANCEL_REQUESTED，
增加 status 索引服务启动扫描。SQLite 修改 CHECK 需要重建父表：短事务复制原 Run、替换父表、
检查 foreign_key_check，再恢复外键 enforcement。原 Step/Checkpoint 及其主键保留。
旧快照中的 request_id 仅用于回填关联字段，不推导 pending 或恢复阶段。

v1 JSON 保留可查，`.state` 和 Resume 不把它假装转换成 v2。
单实例启动收敛是当前学习版策略，同一个数据库只能由一个 Runtime 服务实例拥有；
另一个进程仅创建 Repository 做查询不会执行 reconciliation。

## E. 关键 Diff 导读

1. **状态机与终态保护**：RunStatus 加入 CANCEL_REQUESTED；Run 增加 retryable/version/request_id。
   正常取消是两段动作。只有明确可重试 FAILED 才允许经 Resume 校验重新进入 RUNNING，CANCELLED 没有此出口。

2. **Repository CAS**：新增 compare_and_set_status(expected_statuses, expected_version)。
   update_run_status 也通过这一条件更新路径执行，避免某个普通进度保存绕过取消或覆盖终态。

3. **CancelRun 的提交顺序**：持久请求返回 CancelDecision，Servicer 根据 accepted 再通知活跃 token。
   保存失败时不发送 signal；重建 Servicer 后查询终态仍来自数据库。

4. **CancellationToken 的角色变化**：原 Event 保留，新增 is_cancelled 供安全点和 Queue bridge 观察。
   DB 意图是权威事实，Event 只改善当前进程的响应时间，不承担重启后恢复。

5. **Loop safe_point**：在阶段、事务和 I/O 边界检查两种信号。save_boundary 会保留已提交的
   CANCEL_REQUESTED，已经成功的 Tool 先保存结果，再停止后续执行；事件队列背压也不会阻止取消观察。

6. **Checkpoint 控制状态**：v2 显式 pending_tool_calls 和 final_answer，next_action 增加 FINALIZE。
   编排从有限阶段分派；messages 仅用于上下文和一致性校验，不能充当隐含的程序计数器。

7. **ResumeRun 的准备与执行**：prepare_resume 在一个事务里加载、验证和 CAS 接管，再保存恢复快照；
   execute_prepared 共用普通运行执行器。重复 Resume 不能并发创建第二个执行者。

8. **retryable 分类**：白名单只包含已知暂时性外部故障和启动中断。ToolRegistry 保留原异常链，
   仅传递安全布尔标记；错误正文不会变成持久失败信息或 gRPC description。

9. **启动收敛与 Schema**：迁移保留数据，不改 Java 业务表；启动前统一结束遗留执行状态，
   不以创建 Repository 或读取 Checkpoint 为由接管正在运行的 worker。

10. **事务与测试**：动作开始、动作完成、Finalize 和恢复接管都有明确短事务。测试用 BaseException
    注入“来不及普通异常收尾”的中断，并验证实际数据库回滚、部分 Tool 恢复和 Java/Python TCP 契约。
    这些事务不包含网络请求，也没有宣称覆盖 Java 业务提交。

## F. 设计取舍

1. **为什么 Cancel 不能等于 kill thread？** 阻塞调用可能已经触达外部系统，强制终止线程会丢失执行结果和
   清理机会。协作式停止让 Runtime 在可记录、可解释的安全边界结束。

2. **为什么需要 CANCEL_REQUESTED？** 请求已经接受和执行已经停止不是同一时刻。
   中间状态可以立即持久化意图、跨重启保留，并在 I/O 返回后准确收敛。

3. **为什么 Cancel 不能 rollback Tool side effect？** Python 无法回滚 Java 已提交的独立业务事务。
   取消只阻止未来动作；补偿需要明确业务规则，不能从取消信号自动推导。

4. **为什么 Resume 不能重跑 User Request？** 已经确定的模型输出、已完成 Tool 和预算会丢失，
   可能重复操作且得到不同结果。恢复必须沿已保存的执行边界继续。

5. **为什么 Checkpoint 需要 phase/next_action？** 同样的消息序列可能处于“模型已返回、工具未执行”或
   “最终结果已确定、尚未提交终态”等不同控制位置，显式阶段消除猜测。

6. **为什么 Step + Checkpoint 应同事务？** 二者描述同一个 Runtime 完成事实，拆开提交会留下
   Step 已成功而恢复状态仍指向旧动作的矛盾窗口。开始时也保存快照，可以保留已消耗预算。

7. **为什么仍解决不了 Java write tool 的不确定窗口？** Java 和 SQLite 是不同事务资源。
   Java 提交后到 Python 记账前仍可崩溃，未来写操作必须由 Java 用稳定 ToolCall ID/幂等键保障业务效果。

8. **为什么 CANCELLED 本阶段不可 Resume？** 主动取消表达停止该 Run；它不同于等待批准或暂停。
   允许直接恢复会混淆用户意图，未来 HITL 应有单独 interrupt 状态和恢复规则。

9. **为什么 reconciliation 只适合单实例？** 它假设数据库中的所有未终止 Run 都属于上一个已退出进程。
   多实例共享数据库时这个假设不成立，会误判其他实例仍活跃的执行。

10. **多实例未来为什么可能需要 lease/heartbeat？** 需要证明哪个 worker 拥有执行权、所有权何时过期，
    并防止旧 worker 在接管后继续写入。这需要租约、心跳以及可能的 fencing，本任务未实现。

## G. 后续任务

- P1-03 Write Tool + Proposal + HITL（已在后续实现，见 `write-tool-proposal-hitl.md`）
- P1-04 更通用的 HITL 类型与恢复策略

## 验收与 Git

自动化验证使用 Fake/Stub，不访问真实 LLM。Python 覆盖原有运行能力、持久取消、满 Queue、安全写失败、
CAS 竞态、Checkpoint 校验、部分批次恢复、Finalize、迁移、启动收敛及断流。Java 相关测试显式启用了
原三个跨语言 smoke 和新增 Resume smoke，后者使用父进程 JUnit TempDir 管理测试数据库。

```powershell
.\.venv\Scripts\python.exe -m pytest agent-service/tests
.\.venv\Scripts\python.exe -m ruff check agent-service
mvn -B -pl devpilot-agent -am test
git diff --check
git status --short --untracked-files=all
```

跨语言测试使用既有 DEVPILOT_AGENT_CROSS_LANGUAGE_SMOKE、DEVPILOT_AGENT_RESILIENCE_SMOKE、
DEVPILOT_AGENT_TOOL_CROSS_LANGUAGE_SMOKE 开关和 DEVPILOT_AGENT_PYTHON；第一个 smoke 需要独立 fake Server。
新增 Resume smoke 复用 RESILIENCE 开关并自行启动测试 Server，不改变生产 Fake Model 的行为。

本次未自动 commit、push 或 merge main。推荐提交消息：

```text
feat(agent): add cooperative cancellation and checkpoint resume
```

### 本次验收结果（2026-09-03）

| 检查 | 结果 |
|---|---|
| Python 全量 pytest | 175 passed |
| Ruff | All checks passed |
| Java 相关 Reactor（含四个跨语言 smoke） | 302 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS |
| 其中 devpilot-agent | 81 tests，全通过 |
| git diff --check | 通过 |
| 学习材料内部文件链接 | 全部目标存在 |

当前分支为 `agent`。本轮相对开始时工作区新增 8 个文件、修改 21 个文件；完整工作区还包含
P1-01 未提交改动，共 28 个已跟踪修改、17 个未跟踪文件。没有执行 commit、push 或合并 main。

### 完整 Git 状态快照

```text
 M .env.example
 M .gitignore
 M README.md
 M agent-service/Dockerfile
 M agent-service/README.md
 M agent-service/examples/deepseek_tool_smoke.py
 M agent-service/src/devpilot_agent_service/config.py
 M agent-service/src/devpilot_agent_service/rpc/application.py
 M agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2.py
 M agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2_grpc.py
 M agent-service/src/devpilot_agent_service/rpc/server.py
 M agent-service/src/devpilot_agent_service/rpc/servicer.py
 M agent-service/src/devpilot_agent_service/rpc/tool_gateway_client.py
 M agent-service/src/devpilot_agent_service/runtime/agent_loop.py
 M agent-service/src/devpilot_agent_service/runtime/cancellation.py
 M agent-service/src/devpilot_agent_service/runtime/errors.py
 M agent-service/src/devpilot_agent_service/tools/registry.py
 M agent-service/tests/cross_language_tool_smoke.py
 M agent-service/tests/test_agent_loop.py
 M agent-service/tests/test_generated_grpc_contract.py
 M agent-service/tests/test_remote_tools.py
 M agent-service/tests/test_rpc_server.py
 M agent-service/tests/test_rpc_servicer.py
 M compose.yaml
 M contracts/agent/v1/README.md
 M contracts/agent/v1/agent_runtime.proto
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageAgentResilienceSmokeTest.java
 M devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageGrpcSmokeTest.java
?? agent-service/docs/cancel-resume.md
?? agent-service/docs/runtime-persistence.md
?? agent-service/src/devpilot_agent_service/runtime/persistence.py
?? agent-service/src/devpilot_agent_service/runtime/recovery.py
?? agent-service/src/devpilot_agent_service/runtime/redaction.py
?? agent-service/src/devpilot_agent_service/runtime/repository.py
?? agent-service/src/devpilot_agent_service/runtime/schema.py
?? agent-service/src/devpilot_agent_service/runtime/sqlite_repository.py
?? agent-service/tests/conftest.py
?? agent-service/tests/cross_language_resume_server.py
?? agent-service/tests/test_cancel_resume.py
?? agent-service/tests/test_cancel_resume_rpc.py
?? agent-service/tests/test_runtime_persistence.py
?? agent-service/tests/test_runtime_recovery.py
?? agent-service/tests/test_runtime_repository.py
?? agent-service/tests/test_runtime_stream_persistence.py
?? devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/CrossLanguageAgentResumeSmokeTest.java
```
