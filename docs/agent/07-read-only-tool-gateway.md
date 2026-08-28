# Agent 第 7 章：Read-only Tool Gateway

## 1. 目标与停止线

P0-06 已能运行模型并把生命周期流给 Browser，但只能使用 Python 本地 EchoTool，尚不能读取 DevPilot 的真实
Project、Task 和 Activity。本章建立第一条真实业务能力链，同时把停止线放在 read-only：

```text
User → Java AgentRun → Python AgentLoop → Remote Tool
→ Java Tool Gateway → run-bound delegation → RBAC → Application Query → DB
→ bounded/untrusted Tool result → Python Model → Final → SSE
```

只开放：`project.get_summary`、`task.list_open`、`project.list_recent_activity`。不实现 Task/Project/GitHub 写入、
Proposal、人工确认、自动 Retry、Cache、MCP、RAG、Memory 或 Multi-Agent。

## 2. Tool 不是 DAO

Tool 表达“当前 actor 在当前 scope 可使用的业务能力”，不是表名、Mapper、Bean 或任意 Java 方法。Python 不连接
Java MySQL，也不能生成 SQL。Java Gateway/Handler 只调用正式 Project/Task Application Query；Mapper 仍封装在
各自业务模块内部，ArchUnit 额外禁止 Tool Gateway/Handler 依赖任何 Mapper。

## 3. 两条 RPC 与端口

```text
Browser → Java HTTP/SSE :8080
Java → Python AgentRuntime.StreamRun :50051
Python → Java DevPilotToolGateway.ExecuteTool :50052（默认 loopback）
```

Java→Python 是委托 Runtime 执行；Python→Java 是 Runtime 申请受控业务 Capability。Tool handler 不会再次启动
AgentRun，也不会在持有 StreamCoordinator/EventHub 锁时查询业务，因此没有 RPC/锁环。

## 4. service identity 与 end-user identity

Python Client 在 metadata 中发送：

```text
x-devpilot-agent-service-key: <DEVPILOT_AGENT_TOOL_SERVICE_KEY>
```

Java interceptor 使用常量时间比较；缺失或错误返回 `UNAUTHENTICATED`，不记录/返回 secret。它证明“调用者是当前
Python Agent 服务”，不证明“某个用户有权限”。共享 key 只来自环境/安全配置，默认 Server 关闭且绑定
`127.0.0.1`；这是双服务求职版本的最小 service identity，不宣称生产零信任，未来可替换为 mTLS/OAuth2。

Browser Bearer Token 只到 Java HTTP 边界，绝不按 Java→Python→Java 路径转发。否则会扩大用户会话凭据暴露面，
并可能进入日志、Prompt 或 Provider 请求。

## 5. run-bound delegation

Python contract 只能提交 requestId、runId、toolCallId、toolName 与 Struct arguments；没有 userId、workspaceId、
projectId、role 或 permission 字段。Java 执行：

```text
runId → AgentRunExecutionContextQuery
→ createdBy/workspaceId/projectId/status（Java 权威）
→ require RUNNING + requestId correlation
→ handler
→ explicit actor Application Query
→ ProjectAuthorizationService/TaskAuthorizationService
```

Run 创建后的权限结果不缓存。成员可能在运行期间被移除、降级，Workspace/Project 也可能禁用，所以每次 Tool
execution 都在能力执行点重新鉴权。service auth 与 user RBAC 缺一不可。

## 6. generic contract 与 allowlist

Tool 参数/结果多态，因此 v1 使用 `google.protobuf.Struct`，避免 string JSON 二次协议；字段号 1 保持兼容。
Java 将 name 显式映射到三个 `AgentToolName`，未知名称稳定失败。禁止 `applicationContext.getBean(toolName)`、反射、
Java 类名、Bean 名、表名或 SQL 作为 Tool name。

`tool_call_id` 从 Provider ToolCall 经 Python Client 到 Java，再由响应原样回显；Python 不一致即归类 `PROTOCOL`。

## 7. explicit actor 查询

既有 HTTP 查询仍从 `CurrentUserProvider`/SecurityContext 获取当前用户；新增入口接受明确 actor：

```text
ProjectService.getProjectForActor
TaskQueryService.listForActor / listOpenForActor
ProjectActivityService.queryTimelineForActor
```

HTTP 方法只是解析当前 actor 后委托同一查询逻辑。显式 actor 方法本身仍调用 Authorization Service，未复制权限规则
或 SQL。Task open 查询在 SQL 层排除 DONE/CANCELED，避免“先 LIMIT 再过滤”遗漏开放任务。

## 8. 结果大小与 Prompt Injection 边界

- list limit 默认 10、范围 1..20；
- Project description、Task title、Activity title/summary 单字段截断；
- Java Core 做 protobuf-free 估算防线，Adapter 再检查 Struct 实际 serialized size；
- Python 对 response ByteSize 再防御，默认最大 64 KiB；
- Tool result 必须带 `external_untrusted_content=true`；
- Provider system prompt 明确 Tool 文本是数据，不是系统/开发者指令。

这里不实现启发式 Prompt Injection 检测器，也不把 Tool arguments/result 放进 SSE。ID/枚举与用户/GitHub 文本混合
时整体按 untrusted 处理更容易守住边界。

## 9. 线程、生命周期和错误

Python AgentLoop 本来就在 worker thread 同步调用 Model/Tool，因此单次 3 秒左右的 Unary blocking Tool RPC 当前可
接受。一个进程级 `grpc.Channel` 被所有 ToolCall 复用，关闭 Runtime 时统一关闭；禁止每次调用新建 Channel。

Java 独立 Netty gRPC Server 使用有界线程池、入站消息限制和 graceful shutdown。handler 只做短 read-only 查询，
不在 Java 等待 Python 的数据库事务中执行。

稳定错误包括 UNKNOWN_TOOL、INVALID_ARGUMENT、RUN_NOT_FOUND、RUN_NOT_ACTIVE、PERMISSION_DENIED、NOT_FOUND、
RESULT_TOO_LARGE、PROTOCOL、INTERNAL；service key 失败单独为 gRPC UNAUTHENTICATED。响应/日志不返回 SQL、堆栈、
类名、参数、结果、JWT、service key 或 Provider secret。本章不自动 Retry，避免重复执行与预算漂移。

## 10. 调用链

```text
Browser
→ Java AgentRunApplicationService
→ AgentRuntime.StreamRun
================ network ================
→ Python AgentRuntimeServicer
→ RunContext(runId, requestId)
→ AgentLoop
→ Remote Tool
→ JavaToolGatewayClient + service-key metadata
================ network ================
→ Java AgentToolGrpcServerLifecycle
→ AgentToolServiceAuthInterceptor
→ DevPilotToolGatewayGrpcService
→ AgentToolApplicationService
→ AgentRunExecutionContextQuery
→ delegated actor/scope + require RUNNING
→ allowlist handler
→ explicit actor Project/Task/Activity Query
→ RBAC → scoped SQL → DB
================ network ================
→ Struct result + echoed callId
→ Tool Result Message（untrusted data）
→ Model Final
→ P0-06 terminal/SSE
```

## 11. Observability 与后续演进

`devpilot.agent.tool.gateway.calls/duration` 只使用 allowlisted tool_name、success/failure 和稳定 failure_kind；
`devpilot.agent.tool.gateway.auth.denied` 记录 service auth 拒绝。禁止 runId、userId、projectId、callId、raw error/result
作为 metrics tag。Spring Cloud/Nacos/Gateway 若在 P0-08 引入，只会改变发现/路由，不会改变本章 Tool→Application
Service→RBAC 的业务边界。
