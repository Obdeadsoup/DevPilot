# 第 4 章：Java → Python Protobuf/gRPC 跨进程调用

## 1. 本章结果与边界

P0-03 的 Provider 与 AgentLoop 都在 Python 进程内，Java `devpilot-agent` 没有 Client，proto 也只有
`request_id → run_id` 占位字段。两边各自单测通过仍不能证明网络、序列化和错误边界可用。

P0-04 第一次形成真实链路：

```text
Java caller
→ AgentRuntimePort
→ GrpcAgentRuntimeClient
→ generated Java BlockingStub
→ reused ManagedChannel
→ TCP + HTTP/2 + Protobuf
→ Python gRPC Server
→ AgentRuntimeServicer
→ AgentRuntimeApplication
→ AgentLoop
→ Model
→ Final
→ StartRunResponse
→ Java AgentRunResult
```

当前只实现同步 Unary `StartRun`。没有 HTTP Controller、AgentRun 表、Browser API、Streaming、Cancel、反向
ToolGateway、业务 Tool、Retry、服务身份、数据库访问、Spring Cloud、Nacos、RAG 或 Multi-Agent。

## 2. RPC 与本地调用

本地方法调用共享进程、内存和类型系统；RPC 跨进程，必须面对序列化、网络断开、部分失败、Deadline、兼容性和
两端独立生命周期。Java 调用 `port.run(command)` 看似普通方法，但 Adapter 后面实际发生 TCP/HTTP2 交换，Python
可能已开始执行而 Java 连接刚好超时。

选择 gRPC 的原因是现有边界需要 Java/Python 双语言、严格版本化 DTO、Unary/未来 Server Streaming，以及明确的
Deadline/Status 语义。这里不需要服务注册、配置中心或复杂治理，因此没有为了一个固定本地 endpoint 引入
Spring Cloud/Nacos。

## 3. Contract-first 与 Protobuf IDL

`contracts/agent/v1/agent_runtime.proto` 是唯一跨语言 Source of Truth：

```protobuf
message StartRunRequest {
  string request_id = 1;
  string run_id = 2;
  string user_input = 3;
}

message StartRunResponse {
  string run_id = 1;
  string final_output = 2;
  RunStatus status = 3;
}
```

既有字段号 `request_id=1`、`run_id=1` 保持不变，新字段只追加新编号。未来删除字段必须 `reserved`，不能复用旧号。
Java 生成 run id 并交给 Python，使未来 Java AgentRun 业务投影无需在网络返回后重新关联另一套 id。

编译期：

```text
agent_runtime.proto
├→ protoc 4.35.1 + grpc-java plugin 1.83.1
│  → target/generated-sources/protobuf/java + grpc-java
└→ grpcio-tools 1.83.0
   → devpilot_agent_service/rpc/generated/*
```

`protoc` 读取 IDL 并生成 message 序列化代码；语言专用 gRPC plugin 再生成 Stub/Servicer 骨架。生成文件不手工修改；
Python 用 `python agent-service/scripts/generate_grpc.py` 重建，Java 在 Maven `generate-sources` 自动重建。

## 4. Channel、Stub、Server 与 Servicer

- `ManagedChannel` 是 Java 到一个远端 endpoint 的长生命周期连接管理器，管理 HTTP/2 connection、name resolution
  和并发 RPC。它不是一次请求。
- BlockingStub 是绑定 Channel 的轻量类型安全调用句柄；每次调用可派生带 Deadline 的 Stub。
- Python `grpc.Server` 管理监听端口和工作线程池。
- `AgentRuntimeServicer` 是 generated Server API 的手工实现，把 protobuf 请求转换到内部应用门面。

Java Spring 启动时只创建一个 `AgentGrpcChannel` Bean，所有 Stub/RPC 复用；Context 关闭时先 `shutdown()` 并等待
5 秒，未结束才 `shutdownNow()`。禁止每次调用 new/shutdown Channel，否则会反复建立 HTTP/2 连接、增加延迟并让
资源生命周期失控。

## 5. DTO 隔离

Java Application Core 只使用：

```text
AgentRunCommand → AgentRuntimePort → AgentRunResult
```

protobuf request/response 只存在于 `GrpcAgentRuntimeClient`。Python 同样只在 Servicer 使用 generated DTO，进入
`AgentRuntimeApplication` 后只剩字符串输入和内部 `RunResult`。这样 proto 演进或 codegen 变化不会把 generated
方法扩散到 Controller/Application 领域代码。

## 6. StartRun 完整生命周期

1. Java 创建非空 `requestId/runId/userInput` 的 `AgentRunCommand`；
2. Adapter 映射为 `StartRunRequest`；
3. BlockingStub 派生显式 Deadline 后发起 Unary RPC；
4. Python Servicer 在进入 AgentLoop 前校验三个字段；
5. Application 门面委托 AgentLoop；
6. fake 模式返回 `fake:<user_input>`，DeepSeek 模式调用现有 Provider；
7. Servicer 返回相同 run id、Final 和 `RUN_STATUS_SUCCEEDED`；
8. Java 检查 run id/status，再映射为内部 `AgentRunResult`。

`AGENT_MODEL_MODE=fake` 只用于确定性单测/联调，仍真实进入 AgentLoop；它不是伪造网络或手写 RPC Response。默认
模式为 `deepseek`，继续只从既有 DeepSeek 环境变量读取配置。

## 7. Deadline、Status 与失败语义

每次 Java RPC 都调用 `withDeadlineAfter`，本章不自动 Retry：

| gRPC Status | Java 稳定分类 | 语义 |
| --- | --- | --- |
| `DEADLINE_EXCEEDED` | `DEADLINE_EXCEEDED` | Java 等待超过边界 |
| `UNAVAILABLE` | `UNAVAILABLE` | Python 未监听、连接中断等 |
| `INVALID_ARGUMENT` | `INVALID_ARGUMENT` | Python 请求边界拒绝空字段 |
| `INTERNAL` | `INTERNAL` | Agent/Provider/Server 脱敏失败 |
| `UNKNOWN`/其他 | `UNKNOWN` | 未识别传输失败 |

特别重要：**gRPC timeout 不等于 Python 一定没有执行。** Deadline 到达时 Tool 或 Provider 可能已经开始甚至完成；
盲目 Retry 会重复计费或副作用。P0-04 只分类，不实现 Retry/Backoff/Circuit Breaker。

Transport Error 表示 RPC 没有得到可信的业务响应；`RunStatus.FAILED` 则是一次成功反序列化的业务级结果。当前
Python 内部失败直接返回脱敏 `INTERNAL`，还没有正式失败 Result/AgentRun 投影。

Python 只向 Java返回固定错误描述；日志记录失败类名和稳定 stop reason，不输出 Provider body、API Key、完整
Tool 参数或私有堆栈。Java 异常消息同样只含稳定 kind。

## 8. Server 配置与启动

Python：

```text
AGENT_GRPC_HOST=0.0.0.0
AGENT_GRPC_PORT=50051
AGENT_MODEL_MODE=deepseek|fake
```

```powershell
$env:AGENT_MODEL_MODE = "fake"
python -m devpilot_agent_service.rpc.server
```

Java：

```yaml
devpilot:
  agent:
    grpc:
      host: localhost
      port: 50051
      deadline: 30s
      plaintext: true
```

Java 已支持 plaintext/TLS mode；Python 本章只启动 insecure Server。`0.0.0.0` 和 plaintext 仅适合本地/受控网络，
没有 Service-to-Service Auth，不能直接作为互联网部署方案。

## 9. 测试分层与跨语言证据

Python 单测覆盖 generated import、正常 StartRun、三个空字段、AgentLoop failure、确定性 fake、Server 配置、真实
TCP Server bootstrap，以及 Stream/Cancel/ToolGateway 的 `UNIMPLEMENTED` 状态。Java 单测覆盖 command/response 映射、
显式 Deadline、五类 Status、协议错、配置绑定和 Channel graceful lifecycle。

真正的跨语言 smoke 使用两个 OS 进程：

```text
Python process (PID 151296, fake Server, 127.0.0.1:50051)
       ↑ TCP / HTTP2 / Protobuf
Java Maven/Surefire process (CrossLanguageGrpcSmokeTest)
```

Java 发送 `hello-cross-language`，Python AgentLoop 返回 `fake:hello-cross-language`，Java 对 run id、Final 和状态
做断言。它没有使用 Java in-process Server，因此能证明两端 codegen、序列化、网络、Servicer 与 Adapter 一起工作。
PID 只是本次本机验收证据，不是持久业务标识；验证结束后该进程已停止。

真实 DeepSeek 跨语言 E2E 需要 API Key 与网络，不作为确定性 Gate；缺 Key 时必须记录 `NOT RUN`。

## 10. 暂未实现的 RPC

generated Stub 中仍能看到 `StreamRun/CancelRun/DevPilotToolGateway`，但没有对应业务实现：

- 手工 Servicer 显式以 `UNIMPLEMENTED` 拒绝 Stream/Cancel，避免不同运行时把生成基类的默认异常表现成其他 Status；
- Python 未注册反向 `DevPilotToolGateway`，调用同样得到 `UNIMPLEMENTED`；
- 不生成虚假 Stream 事件或取消成功；
- Python 仍不连接 `dp_*` 数据库，也不能调用 Java Mapper；
- P0-06 再设计真正 AgentEvent sequence/final/error 和 Server Streaming；
- P0-07 再通过 Java Tool Gateway 接正式 Application Service。

## 11. 关键 Diff 导读

P0-03 只有进程内 AgentLoop，所以真实网络仍是空白。P0-04 先把 proto 从 identity 占位扩成同步输入/输出，再由
Maven 与 grpcio-tools 生成两端代码。Java Stub 是调用 API，Channel 是可复用传输资源；Python generated Servicer
是接口骨架，手工 `AgentRuntimeServicer` 才负责校验和委托。强制 Deadline 防止 Java 无限等待，Status Adapter
防止 gRPC 类型和远端描述进入核心。DTO 隔离让业务层不依赖 wire model；固定 endpoint 足以完成首次 RPC，因而
无需 Nacos/Spring Cloud。两个独立进程的 smoke 是“真正跨进程”的最终证据。

推荐阅读顺序：

1. `contracts/agent/v1/agent_runtime.proto`；
2. Java `AgentRuntimePort` 及 Command/Result；
3. `GrpcAgentRuntimeClient`；
4. `AgentGrpcProperties/Channel/Configuration`；
5. Python `AgentRuntimeServicer`；
6. Python `server.py` 与 `application.py`；
7. `scripts/generate_grpc.py`；
8. generated code（只浏览接口）；
9. Java tests；
10. Python tests；
11. `CrossLanguageGrpcSmokeTest`；
12. 本文和文件地图。

## 12. 后续演进

P0-05 可加入 Java AgentRun 业务投影、权限/审计与正式 Application Service；P0-06 再实现 Python Server Streaming
到 Java/SSE 的事件链路，并定义 sequence、终态、错误和断线语义。所有演进继续先改同一个 proto，保持字段号兼容。
