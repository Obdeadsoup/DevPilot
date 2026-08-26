# 第 6 章：gRPC Server Streaming → Java SSE → Browser

## 1. 本章结果与边界

P0-05 的 POST 在 HTTP 线程中等待 Unary `StartRun`，只能在 Python 完成后返回终态。P0-06 把正式浏览器链路改成：

```text
Browser POST
→ AgentRunController
→ CurrentUser / AGENT_PROPOSE
→ Tx#1 INSERT RUNNING COMMIT
→ AgentRunStreamCoordinator
→ AgentRuntimeStreamingPort
→ Java async Stub.streamRun
→ HTTP 202 + RUNNING/runId
```

```text
Python StreamRun
→ bounded Queue bridge
→ worker thread → AgentLoop
→ RuntimeEvent hook
→ protobuf AgentEvent
→ Java StreamObserver.onNext
→ Coordinator
→ run-scoped EventHub
→ SseEmitter
→ Browser
```

本章只流 Agent 生命周期，不是 LLM token delta。Unary `StartRun` 继续用于回归、教学和 smoke；没有实现 Cancel、
Tool Gateway、写 Tool/HITL、事件表、跨实例 SSE、Retry/Reconciliation、RAG、Memory 或 Multi-Agent。

## 2. Unary 与 Server Streaming

Unary 是一请求一响应，BlockingStub 会占住调用线程直到 Final、Deadline 或失败。Server Streaming 是一请求多响应，
Python 可以按生命周期逐个发送 `AgentEvent`；Java 使用 async Stub，把 `onNext/onError/onCompleted` 交给 callback，
因此 POST 不需要用 blocking iterator 占住 HTTP 线程。BlockingStub 与 async Stub 都是轻量调用句柄，并复用同一个
长生命周期 `ManagedChannel`；Streaming 有独立的 10 分钟 Deadline，仍然不是无限等待。

## 3. 三层事件模型与安全边界

```text
RuntimeEvent（Python Core）
→ AgentEvent（protobuf wire）
→ AgentStreamEvent（Java Core）
→ AgentRunSseEventData（Browser DTO）
```

`AgentLoop` 只知道 Provider-neutral `RuntimeEvent`，不知道 protobuf、gRPC、runId 或浏览器。hook 只发
`MODEL_STEP_STARTED`、`TOOL_STARTED`、`TOOL_COMPLETED`；原有不传 hook 的调用完全兼容。Servicer 添加
`RUN_STARTED` 和唯一 terminal，再生成 sequence/eventId。Java Adapter 截止 generated DTO，Coordinator 与 SSE
均不依赖 protobuf。

允许流出的字段只有 run 生命周期、step、受约束的 tool name、final output 和稳定 failure kind。代码禁止把
chain-of-thought/reasoning、system prompt、API Key/JWT、Provider raw body、Tool 参数/结果或 stack trace 放入事件。
Java 还对白名单 failure kind、tool name 格式和 final output 长度做协议校验，不能把任意远端字符串直接推进 SSE。

## 4. Python Queue bridge

现有 AgentLoop 是同步实现，本章没有为 Streaming 全面 async 重构。`StreamRun` 校验三个必填字段后创建容量 64 的
`queue.Queue`，并在 daemon worker thread 运行 Loop。Runtime hook 向 Queue 投递，Servicer generator 从 Queue 取出
并 yield protobuf 事件。gRPC Server 的 8 个 worker 限定并发 Stream 数，因此不会形成无界全局线程池。

Queue 满时 producer 用短 timeout 等待，形成明确背压；一旦 `context.is_active()` 为 false，callback 停止投递，
避免 Browser/Java 断线后 producer 永久卡在满 Queue。本章不取消仍在执行的 AgentLoop。

成功流：

```text
RUN_STARTED → lifecycle events → RUN_SUCCEEDED(final_output) → normal onCompleted
```

业务失败流：

```text
RUN_STARTED → lifecycle events → RUN_FAILED(stable kind) → normal onCompleted
```

只有 transport/server 边界失败才走 gRPC error。

## 5. sequence、eventId 与唯一 terminal

同一 run 的 sequence 从 1 严格递增，eventId 固定为 `runId:sequence`。Coordinator 同时检查：

- 第一个事件必须是 `RUN_STARTED`，且不能再次出现；
- runId 必须等于 Java 发起的 runId；
- sequence 不能重复、倒退或 gap；
- eventId 必须与 runId/sequence 一致；
- 每种事件只能携带其 schema 允许的字段；
- `RUN_SUCCEEDED/RUN_FAILED` 恰好一次且必须最后；
- `onCompleted` 前必须已经看到 terminal。

terminal 前的任何违规都转为 `PROTOCOL`：Tx#2 把 RUNNING 投影置为 FAILED，并发布同一 sequence 空间中的 synthetic
`run-failed`。terminal 已提交后出现第二 terminal、后续事件或 `onError`，不能越过数据库 version 条件覆盖终态。

## 6. 异步 POST 与 terminal 持久化

`AgentRunApplicationService` 仍不加 `@Transactional`。它先调用独立 Bean 的 `createRunning()` 完成 Tx#1，再让
Coordinator 启动 async RPC，最后直接返回最初的 RUNNING view：

```text
POST → RBAC → Tx#1 RUNNING COMMIT → start async stream → 202 RUNNING
```

成功 terminal：

```text
RUN_SUCCEEDED → Java onNext → Tx#2 markSucceeded → SSE run-succeeded → onCompleted
```

失败 terminal：

```text
RUN_FAILED → Java onNext → Tx#2 markFailed(REMOTE_FAILED) → SSE run-failed
```

传输失败：

```text
gRPC onError → DEADLINE/UNAVAILABLE/... → Tx#2 FAILED → synthetic SSE run-failed
```

Mapper 原有 `workspace + project + run + RUNNING + version=0` 条件更新仍是最终并发兜底。Python 不连接 `dp_*`，
每个 model/tool 生命周期也不写 MySQL/Outbox。

## 7. Java SSE 边界

SSE endpoint 为：

```http
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs/{runId}/stream
Accept: text/event-stream
```

Controller 先通过 `AgentRunApplicationService.get()` 完成 CurrentUser、`AGENT_READ` 和
workspace/project/run scoped 查询，再注册 emitter。仅知道全局 runId 不能建流。事件名为 `run-started`、
`model-step-started`、`tool-started`、`tool-completed`、`run-succeeded`、`run-failed`、`heartbeat` 和
`replay-gap`。

`AgentRunEventHub` 独立于 Notification Registry。它按 run 保存多连接、清理 completion/timeout/error、限制每 run
最多 5 个连接并淘汰最旧连接、暴露低基数 send/connection 指标，在 `@PreDestroy` 关闭全部 emitter。Heartbeat
默认每 20 秒发送，无 id/sequence、不落库、不进 replay；坏连接会被清理。

## 8. Last-Event-ID 与有界 replay

Hub 每 run 默认只保留最近 64 个 Agent 事件。客户端带：

```http
Last-Event-ID: runUuid:5
```

时，服务先校验 id 格式和所属 run，再原子 replay `sequence > 5` 的缓存，之后进入 live。注册与 publish 在同一
run 状态锁内排序，避免 replay/live 窗口丢事件。terminal 缓存保留 10 分钟，重连可重放终态后结束连接。

若请求早于最旧事件、超前于最新事件，或 Java 重启后没有该 run 的内存状态，Hub 发 `replay-gap`，明确要求客户端
调用 scoped GET。它不会假装能从非持久缓存完整恢复；本章也没有新增 AgentEvent 数据库表。

## 9. 三层 backpressure 与断线语义

```text
Python Runtime → capacity=64 Queue，满时有界等待，断线后停止投递
gRPC → HTTP/2 flow control + 独立 stream Deadline
Java SSE → capacity=64 replay + 每 run 连接上限 + timeout + broken-client cleanup
```

没有引入 Reactor、Kafka、无界事件队列或无界业务线程池。Java 直接按同一 gRPC callback 顺序校验、短事务持久化和
发布，保持同 run 有序。

Browser tab close、网络抖动或 EventSource reconnect 只代表 emitter 断开，不代表用户明确 Cancel。Python Run
继续执行，Java 仍会接收 terminal 并投影数据库。Cancel 需要单独的权限、幂等和歧义模型，留到后续章节。

## 10. AgentRun 数据库仍是 Source of Truth

SSE 和 replay deque 都是 best-effort delivery cache。事件丢失、Java restart、replay gap 或 TTL 过期不会改变
`dp_agent_run` 的业务事实。客户端始终可用 GET 获取 `RUNNING/SUCCEEDED/FAILED + finalOutput/failureKind`。
这也是没有把每个 model/tool event 写入 MySQL 或 Outbox 的原因。

## 11. 测试分层

- Python：hook、请求校验、RUN_STARTED first、严格 sequence/eventId、model/tool lifecycle、成功/失败唯一 terminal、
  Queue 容量、context cancel 释放 producer、Unary 回归和真实 loopback TCP Server。
- Java Adapter/Coordinator：request mapping、async Deadline、protobuf 隔离、Status 映射、runId/sequence/eventId/payload、
  重复/gap/terminal、onCompleted invariant、终态持久化和 terminal 后 onError 不覆盖。
- SSE/Boot：连接限制、Heartbeat、TTL、replay/replay-gap/terminal replay、202 RUNNING、事务外建流、401/403/scope、
  id/name/DTO 以及 GET 权威终态。
- 跨语言：独立 Python 进程与 Java Surefire 进程经 TCP/HTTP2 验证 Unary 回归和 Server Streaming 全序列。

## 12. 推荐阅读与 Diff 导读

1. `contracts/agent/v1/agent_runtime.proto`：先看 wire schema 和字段号；
2. `runtime/events.py`、`runtime/agent_loop.py`：看为什么 RuntimeEvent 不用 protobuf；
3. `rpc/servicer.py`：看同步 Loop 如何经 Queue 实时 yield；
4. `AgentRuntimeStreamingPort`、`GrpcAgentRuntimeStreamingClient`：看 async Stub/StreamObserver 隔离；
5. `AgentRunStreamCoordinator`：看 sequence、terminal、onError 与 Tx#2；
6. `AgentRunEventHub`、`AgentRunStreamController`：看 scoped SSE、replay 和 Heartbeat；
7. `AgentRunApplicationService`：对比同步 POST 到 202 RUNNING 的最小 Diff；
8. tests、generated interface、本文与文件地图。

同步 POST 必须升级，是因为浏览器不能等整个模型/工具循环才获得 runId；async Stub 正好把 Server Streaming 的
`onNext/onError/onCompleted` 从 HTTP 请求线程解耦。Browser 仍只访问 Java，因为身份、RBAC、scope、业务投影和
安全 DTO 都由 Java 掌握。replay 有界且不持久，是因为它只优化实时体验；SSE 丢失不会破坏数据库事实。当前没有
token streaming，是因为 Provider 原生 delta、内容安全和更细背压语义尚未设计，未来可兼容追加 `OUTPUT_DELTA`。
