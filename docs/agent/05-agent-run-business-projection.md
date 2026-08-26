# 第 5 章：AgentRun 业务投影与 HTTP API

## 1. 本章结果与边界

P0-05 把第 4 章的 Java → Python Unary gRPC 边界接入正式 Java Application Core。现在已认证用户可以在
Project scope 内启动 Agent Run，并查询 Java 拥有的用户可见结果：

```text
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs/{runId}
```

POST 使用 `AGENT_PROPOSE`，GET 使用 `AGENT_READ`。Controller 不读取 Mapper，客户端不能提交 userId、runId、
requestId、status 或 version。本章没有增加列表、Streaming/SSE、Cancel、Tool Gateway、自动 Retry、业务写 Tool、
人工确认、知识索引或新的 Python 能力。

## 2. Java 与 Python 的数据所有权

`dp_agent_run` 是 Java 拥有的业务投影，记录当前用户、Workspace/Project scope、输入、稳定状态和用户可查询结果。
Python 仍只拥有执行期 Loop/Provider 上下文，不能连接或更新 `dp_*`。跨进程数据继续只走
`contracts/agent/v1/agent_runtime.proto`。

这不是把 Python 内部执行状态复制成另一套真相。Java 投影回答“谁在什么 Project 发起了什么 Run，以及 Java
观察到的终态”；Python 未来的 step/checkpoint 则回答 Runtime 内部如何执行。

## 3. 表与状态机

Flyway V14 新增 `dp_agent_run`：

- `run_id`、`request_id` 各有唯一键，均由 Java 在 RPC 前生成；
- `workspace_id + project_id` 复合外键指向 Project scope，`created_by` 指向用户；
- 状态只能 `RUNNING → SUCCEEDED` 或 `RUNNING → FAILED`；
- `version=0` 表示 RUNNING，唯一一次条件终态更新后为 1；
- CHECK 保证 RUNNING 没有终态字段，SUCCEEDED 只有 `final_output`，FAILED 只有稳定 `failure_kind`；
- scope/time 与 scope/status/time 索引服务后续分页和运行状态排查。

当前不提供通用 `updateStatus`。Mapper 的两个终态 SQL 都要求同时命中：

```text
run_id + workspace_id + project_id + status=RUNNING + version=0 + deleted=0
```

因此并发或重复终态写入最多一次成功，失败转换为 `AGENT_0501`，而不是静默覆盖。

## 4. 分段事务与真实调用链

```text
HTTP Controller
→ CurrentUserProvider
→ ProjectAuthorizationService
→ AgentRunPersistenceService.createRunning()       [事务 1，提交]
→ AgentRuntimePort.run()                            [无数据库事务，gRPC]
→ AgentRunPersistenceService.markSucceeded/Failed  [事务 2，提交]
→ AgentRunResponse
```

`AgentRunApplicationService` 故意不加 `@Transactional`；三个事务方法位于独立 Spring Bean，避免同类方法自调用让
代理失效。RUNNING 先提交，使 RPC 期间不占用数据库事务/连接，也让进程在远端调用期间崩溃时留下可诊断投影。
网络调用完成后再用短事务写终态。

同步 POST 返回终态投影。已分类的 gRPC 失败也返回 HTTP 200 的 `FAILED` AgentRun，因为 Run 已成功创建并可通过
GET 查询；请求校验、认证、授权、scope 不存在和并发冲突仍走统一 HTTP 错误响应。

## 5. 权限与作用域

- 未认证请求由既有 Spring Security 返回 401；
- POST 必须有 `AGENT_PROPOSE`，Project Developer 及以上可用；
- GET 必须有 `AGENT_READ`，Project Viewer 及以上可用；
- 权限继承、PRIVATE/INTERNAL 可见性和 ARCHIVED 只读降级复用既有 Project RBAC；
- 每一条读取和更新 SQL 都携带 `workspaceId + projectId`，不能只凭全局 runId 越 scope 查询。

身份字段只用于关联，不能提升权限。即使知道另一个 Project 的 runId，有权限访问当前 Project 的用户也只能得到
当前 scope 内的 404。

## 6. 失败、安全与 Deadline

持久化失败分类只有：`REMOTE_FAILED`、`DEADLINE_EXCEEDED`、`UNAVAILABLE`、`INVALID_ARGUMENT`、`INTERNAL`、
`UNKNOWN`、`PROTOCOL`。数据库和 HTTP 都不保存/返回 gRPC description、Provider body、堆栈、Token 或 Secret。
Python 返回业务级 FAILED 时也不会把 `final_output` 当错误详情落库。

未预期的 Java RuntimeException 会先尽力把 Run 标记为 `UNKNOWN`，随后原异常继续抛出，避免吞掉编程错误。
若终态投影也失败，投影异常只作为 suppressed error 保留。

Deadline 到达只证明 Java 停止等待，不能证明 Python 未执行。因此本章不自动 Retry。此时 Java 投影为
`FAILED/DEADLINE_EXCEEDED`；未来若增加重试或对账，必须先定义 requestId 幂等与远端状态查询。

## 7. 请求与响应

请求只接受一个非空且最多 10000 字符的 `input`：

```json
{"input":"总结当前 Project 的风险"}
```

响应继续使用统一 `ApiResponse`，data 包含 runId/requestId、scope、createdBy、status、输入、成功输出或稳定失败类型、
时间和 version。持久化实体不会直接作为 API Response。

## 8. 测试证据

Agent 模块单测覆盖输入校验、统一响应转换、权限前置、RUNNING/RPC/终态顺序、成功、业务失败、Deadline、未知异常、
唯一键冲突和第二次终态冲突。Boot Testcontainers 验收覆盖 V14/index、真实 Spring MVC、Project Developer/Viewer/
Outsider 权限、跨 Project scope、MySQL 持久化、脱敏失败可查询，以及 RPC 回调时
`TransactionSynchronizationManager.isActualTransactionActive() == false`。

本机 Docker daemon 不可用时 Testcontainers 按项目约定跳过；测试源码仍会编译，完整环境应从空 MySQL 执行 V1→V14。

## 9. 三条调用链

成功：

```text
HTTP → Controller → Application → CurrentUser → AGENT_PROPOSE
→ Tx#1 RUNNING COMMIT → AgentRuntimePort → gRPC → Python AgentLoop
→ AgentRunResult → Tx#2 SUCCEEDED COMMIT → HTTP Response
```

失败：

```text
HTTP → Tx#1 RUNNING COMMIT → gRPC × DEADLINE/UNAVAILABLE/PROTOCOL
→ stable failureKind → Tx#2 FAILED COMMIT → FAILED Response/后续 GET
```

查询：

```text
GET → Application → CurrentUser → AGENT_READ
→ findByScope(workspaceId, projectId, runId) → Response
```

## 10. Diff 导读

P0-04 只证明 Java/Python RPC 可用，没有浏览器入口、用户身份、Project 权限或用户可查询业务记录，因此还不是完整
功能。P0-05 把权威 AgentRun 放在 Java，是因为 Java 已拥有身份、RBAC、Workspace/Project 和用户可见业务 API；
Python Runtime State 只描述执行细节，不能取代业务投影。

启动可能消耗模型资源并产生后续 Proposal，所以使用写性质的 `AGENT_PROPOSE`；查看已存在结果只需只读的
`AGENT_READ`。单个 `@Transactional` 若包住 gRPC，会让数据库连接和锁跨越不可控网络等待；RUNNING 先提交后，
崩溃和 timeout 也会留下可诊断记录。成功写 finalOutput，失败只写稳定 failureKind。Deadline 仍是 ambiguous
outcome，因此不能自动重试。P0-06 可在保持同一 Java 投影和权限边界的前提下，把同步等待升级为 Server Streaming
与 Java SSE。

## 11. 后续演进

P0-06 若实现 Server Streaming，需要先定义 AgentEvent sequence、重复/乱序、断线恢复、唯一终态和 Java → SSE
背压边界。P0-07 若实现 Tool Gateway，读 Tool 必须继承当前用户权限，写 Tool 先创建 Proposal，高风险写入使用短期
一次性确认令牌并审计。当前同步 AgentRun 不能冒充这些能力。
