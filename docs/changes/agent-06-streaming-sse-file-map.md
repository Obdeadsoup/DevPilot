# P0-06 gRPC Streaming 与 AgentRun SSE 文件地图

## 基线与范围

- 分支/实际基线：`agent` / `b7095ab2f3b4b2ae1d3aa55e051839a0590fadd7`，起始工作树 clean。
- 范围：Agent lifecycle Server Streaming、Java async Stub、异步 POST、终态投影、run-scoped SSE、Heartbeat、
  有界 replay、`Last-Event-ID`、跨语言 E2E。
- 未新增数据库迁移或事件表；未实现 token delta、Cancel、Tool Gateway、HITL、跨实例广播或自动 Retry。

## Contract、Python 与 generated

| 标记/文件 | 职责、调用关系与关键方法 | 异步/RPC/顺序/安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [MOD] `contracts/agent/v1/agent_runtime.proto` | StreamRunRequest 增加 request/input；AgentEvent 增加 run/sequence/type/step/tool/final/failure | 唯一 wire schema；保留字段 1 并追加编号；无 payload_json | 双端 contract、E2E |
| [MOD] `contracts/agent/v1/README.md` | 契约当前能力和演进规则 | 标明 StreamRun 已实现、Cancel/Gateway 未实现 | 文档审查 |
| [NEW] `runtime/events.py` | RuntimeEventType/RuntimeEvent；AgentLoop hook → Queue bridge | 无 runId/protobuf；不含 reasoning、参数或结果 | `test_agent_loop.py` |
| [MOD] `runtime/agent_loop.py` | `run(..., on_event=None)` 发 model/tool lifecycle | 同步 Loop；旧调用兼容；hook 只观察公开元数据 | Loop 全回归/hook |
| [MOD] `rpc/application.py` | Servicer → Application → AgentLoop，透传可选 hook | 无 protobuf；run identity 不进 Prompt | RPC tests |
| [MOD] `rpc/servicer.py` | StreamRun 校验、capacity=64 Queue、worker、yield AgentEvent | Server worker + run worker 边界；context inactive 停投；唯一 terminal | servicer/server/E2E |
| [GENERATED] `rpc/generated/agent_runtime_pb2.py` | grpcio-tools 从共享 proto 生成 message/enum descriptor | 不手改；wire serialization | Python contract/E2E |
| [GENERATED] `rpc/generated/agent_runtime_pb2_grpc.py` | 既有方法的 generated Stub/Servicer 骨架 | StreamRun 方法形状未变；业务仍在手工 Servicer | import/server/E2E |
| [MOD] `tests/test_agent_loop.py` | RuntimeEvent hook 顺序和脱敏 | 证明无 hook 回归与公开字段边界 | 文件自身 |
| [MOD] `tests/test_generated_grpc_contract.py` | 校验 Stream request/event schema | 防 codegen 漂移 | 文件自身 |
| [MOD] `tests/test_rpc_servicer.py` | 校验字段、sequence/eventId、tool lifecycle、成功失败、cancelled Queue | 直接消费 generator；容量降为 1 验证断线释放 producer | 文件自身 |
| [MOD] `tests/test_rpc_server.py` | loopback TCP StreamRun 与 Unary/Cancel/Gateway 边界 | 真 TCP Python Server；Cancel/Gateway 仍 UNIMPLEMENTED | 文件自身 |

以上 Python 路径前缀分别为 `agent-service/src/devpilot_agent_service/` 与 `agent-service/tests/`。

## Java Core、gRPC 与异步 POST

| 标记/文件 | 职责、调用关系与关键方法 | 线程/事务/顺序/安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [NEW] `application/AgentStreamEventType.java` | Java Core 生命周期 enum | 与 protobuf 隔离；标识 terminal | Adapter/Coordinator |
| [NEW] `application/AgentStreamEvent.java` | wire 映射后的内部事件 | 不依赖 generated DTO；Coordinator 校验字段 | Adapter/Coordinator |
| [NEW] `application/AgentRuntimeStreamFailureKind.java` | Core 稳定 transport taxonomy | 不扩散 gRPC description | Adapter/Coordinator |
| [NEW] `application/AgentRuntimeEventListener.java` | async callback Port | `onEvent/onError/onCompleted`，无 StreamObserver | Adapter/Coordinator |
| [NEW] `application/AgentRuntimeStreamingPort.java` | Coordinator → Runtime async 出站 Port | `stream()` 发起即返回 | App/Adapter tests |
| [NEW] `application/AgentRunEventPublisher.java` | Coordinator → SSE Hub Port | RPC callback 不依赖 SseEmitter | Coordinator tests |
| [NEW] `application/AgentRunStreamCoordinator.java` | 建流、协议仲裁、Tx#2、synthetic failure | 同 run 同步 callback；terminal 先 DB 后 SSE；version=0 兜底 | 9 个协议/终态 tests |
| [MOD] `application/AgentRunApplicationService.java` | RBAC → Tx#1 → Coordinator → RUNNING | 自身无事务；不等待 terminal | Application/Boot |
| [MOD] `application/AgentRunCommand.java` | Unary 与 Streaming 共享内部命令 | identity 由 Java 生成，不提升权限 | Adapter/E2E |
| [NEW] `infrastructure/grpc/GrpcAgentRuntimeStreamingClient.java` | Command → StreamRunRequest；AgentEvent → Core listener | async Stub + 独立 Deadline；callback/status 脱敏 | Adapter/E2E |
| [MOD] `infrastructure/grpc/AgentGrpcProperties.java` | 增加 streamDeadline | 有限且独立于 Unary | Properties/config |
| [MOD] `infrastructure/grpc/AgentGrpcConfiguration.java` | 同 Channel 装配 BlockingStub + async Stub + 两个 Port | Channel 进程级复用 | Configuration test |
| [MOD] `api/AgentRunController.java` | POST 返回 `202 Accepted` | 响应只含已提交 RUNNING | Controller/Boot |
| [MOD] `error/AgentRunErrorCode.java` | 增加非法 Last-Event-ID 400 | 稳定 API 错误，不回显原 header | Controller/Boot |
| [MOD] `pom.xml` | Agent 模块显式增加 micrometer-core | 只为 SSE 连接/send 指标；无新框架 | Maven build |

以上 Java 生产路径前缀为
`devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/`。

## Java SSE Hub 与 API

| 标记/文件 | 职责、调用关系与关键方法 | SSE/并发/replay/安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [NEW] `config/AgentRunSseProperties.java` | timeout/heartbeat/connection/replay/TTL 配置 | 正数和容量上限校验 | context/Hub |
| [NEW] `config/AgentRunStreamConfiguration.java` | 注册配置属性 | 不依赖 Notification 模块 | Boot context |
| [NEW] `sse/AgentRunEventHub.java` | run→connections+deque；register/publish/heartbeat/cleanup | per-run 锁排序 replay/live；容量 64；terminal TTL；PreDestroy | Hub/Boot SSE |
| [NEW] `sse/AgentRunStreamMetrics.java` | connection Gauge、低基数 send counter | 指标不含 runId/input/output | Hub/context |
| [NEW] `sse/AgentRunSseEventData.java` | AgentStreamEvent → Browser DTO | 不暴露 protobuf | Boot SSE body |
| [NEW] `sse/AgentRunReplayGapSseData.java` | replay-gap 提示 GET 权威状态 | 不承诺完整恢复 | Hub/Boot |
| [NEW] `sse/AgentRunHeartbeatSseData.java` | 无 sequence 的 Heartbeat DTO | 不入 DB/replay | Hub test |
| [NEW] `sse/AgentRunSseHeartbeatScheduler.java` | 周期 heartbeat + TTL cleanup | 失败只清 emitter；test profile 可关闭 | Hub/context |
| [NEW] `api/AgentRunStreamController.java` | scoped GET 校验后注册 emitter；解析 Last-Event-ID | CurrentUser/AGENT_READ/scope 复用 Application Service | Controller/Boot |

## 配置、测试与文档

| 标记/文件 | 职责与边界 | 证据 |
| --- | --- | --- |
| [MOD] `.env.example` | 增加非敏感 stream Deadline 占位 | 配置绑定 |
| [MOD] `.gitignore` | 精确放行第 6 章两份 docs | `git status/check-ignore` |
| [MOD] `devpilot-boot/.../application.yml` | stream Deadline 与 SSE 安全默认 | Boot context |
| [MOD] `devpilot-boot/.../application-test.yml` | 关闭 test profile Scheduler | Boot context |
| [MOD] `AgentRunControllerTest.java` | 202/RUNNING response | 文件自身 |
| [NEW] `AgentRunStreamControllerTest.java` | scope 前置与 Last-Event-ID | 文件自身 |
| [MOD] `AgentRunApplicationServiceTest.java` | Tx#1 → start stream → RUNNING | 文件自身 |
| [NEW] `AgentRunStreamCoordinatorTest.java` | mismatch/duplicate/gap/terminal/onError/onCompleted/DB | 文件自身 |
| [MOD] `AgentGrpcPropertiesTest.java` | 两类 Deadline | 文件自身 |
| [MOD] `AgentGrpcConfigurationTest.java` | 一个 Channel、两种 Stub/Port | 文件自身 |
| [NEW] `GrpcAgentRuntimeStreamingClientTest.java` | request/event/status/callback mapping | 文件自身 |
| [MOD] `GeneratedContractTest.java` | Java Stream contract/codegen | 文件自身 |
| [NEW] `AgentRunEventHubTest.java` | connection limit/replay/gap/terminal/heartbeat/TTL | 文件自身 |
| [NEW] `AgentRunSseMvcTest.java` | 无 Docker 的真实 MockMvc async SSE wire 格式 | id/name/data/heartbeat |
| [MOD] `CrossLanguageGrpcSmokeTest.java` | 独立进程 Unary 回归 + Server Streaming | 条件式真实 TCP E2E |
| [MOD] `devpilot-boot/.../AgentRunHttpIntegrationTest.java` | MySQL/MVC/RBAC/202/SSE/replay/terminal GET | Docker 环境验收 |
| [MOD] `devpilot-boot/.../DevPilotApplicationTests.java` | Coordinator/Hub Spring 装配 | context test |
| [MOD] `README.md` | 对外 API、当前能力和限制 | 文档审查 |
| [MOD] `agent-service/README.md` | Queue Streaming 运行/codegen说明 | 文档命令实跑 |
| [NEW] `docs/agent/06-grpc-streaming-sse.md` | 架构、Diff 导读和学习说明 | 与代码/测试互校 |
| [NEW] `docs/changes/agent-06-streaming-sse-file-map.md` | 本文件 | 最终验证记录 |

## Generated Java 文件

Maven 从同一 proto 重建 `target/generated-sources/protobuf/java` 与 `grpc-java`。关键接口包括
`AgentRuntimeGrpc.AgentRuntimeStub.streamRun(StreamRunRequest, StreamObserver<AgentEvent>)`、更新后的
`StreamRunRequest/OrBuilder`、`AgentEvent/OrBuilder` 和新增 `AgentEventType`。这些均标记为 [GENERATED]，只浏览接口，
不提交业务规则或手工修改。

## 四条调用链

```text
Browser POST → Controller → AGENT_PROPOSE → Tx#1 RUNNING COMMIT
→ Coordinator → StreamingPort → Async Stub.streamRun → HTTP 202 RUNNING
```

```text
Python StreamRun → bounded Queue → AgentLoop → RuntimeEvent
→ protobuf AgentEvent → Java onNext → EventHub → SseEmitter → Browser
```

```text
RUN_SUCCEEDED → Java onNext → Tx#2 SUCCEEDED → SSE run-succeeded → onCompleted
```

```text
gRPC onError → stable failure → Tx#2 FAILED → synthetic SSE run-failed
```

## 验证记录

| 状态 | 命令/场景 | 实际结果 |
| --- | --- | --- |
| PASS | `python agent-service/scripts/generate_grpc.py` | grpcio-tools 1.83.0 从共享 proto 重建 pb2/pb2_grpc |
| PASS | `python -m pytest agent-service/tests` | 80 passed；含 Queue cancel、真实 Python loopback Streaming 与 Unary 回归 |
| PASS | `python -m ruff check agent-service` | All checks passed |
| PASS | `mvn -pl devpilot-agent -am test` | 最终 53 tests：52 passed，条件式跨语言入口 skipped 1 |
| PASS | Boot context 定向测试 | Coordinator/Hub/两种 Stub 完成 Spring 装配，1 passed |
| ENV SKIP | `AgentRunHttpIntegrationTest` | 6 个 MySQL/MVC/SSE 用例已编译；本机无 Docker daemon，Testcontainers 全部跳过 |
| PASS | 真实双进程跨语言 E2E | 独立 Python fake Server + Java Surefire，经 TCP/HTTP2 验证 Unary 与 Streaming；1 passed，进程已停止 |
| PASS | `mvn clean verify` | 11 模块全部 SUCCESS；403 tests，296 passed、107 skipped（含 106 Docker 与 1 条件 E2E） |
| PASS | `docker compose config --quiet` | exit 0，Compose 配置可解析 |
| PASS | `git diff --check` 与边界扫描 | 无空白错误；Java Core 无 protobuf/gRPC import，Python Runtime 无 grpc/protobuf import |

最终 Agent 模块共 53 tests（52 passed、1 条件 E2E skipped），并已纳入上述全量 Reactor 验证。
Docker 跳过不冒充 MySQL/SSE 集成执行成功。
