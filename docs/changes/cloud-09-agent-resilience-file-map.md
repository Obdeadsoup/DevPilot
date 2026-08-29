# P0-09 Agent Resilience 文件地图

标记：`[NEW]` 新增，`[MOD]` 修改，`[GENERATED]` 由 proto 工具生成。

## Contract 与数据库

- `[MOD] contracts/agent/v1/agent_runtime.proto`：CancelRun 增加 `request_id/status`，新增 `RUN_CANCELLED`；Java/Python 唯一 RPC 契约来源。
- `[GENERATED] agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2.py`：Python message/enum。
- `[GENERATED] agent-service/src/devpilot_agent_service/rpc/generated/agent_runtime_pb2_grpc.py`：Python stub/servicer。
- `[NEW] devpilot-boot/src/main/resources/db/migration/V15__add_agent_run_cancelled_status.sql`：允许 `CANCELLED` 且约束 terminal 字段一致性。

## Java Core / API

- `[MOD] devpilot-agent/.../application/AgentRunStatus.java`、`AgentRunFailureKind.java`、`AgentRuntimeStreamFailureKind.java`：业务状态与稳定失败分类。
- `[NEW] devpilot-agent/.../application/AgentRuntimeCancelCommand.java`、`AgentRuntimeCancelStatus.java`、`AgentRuntimeCancellationPort.java`、`AgentRuntimeCancellationException.java`：Core 不依赖 protobuf 的取消边界。
- `[NEW] devpilot-agent/.../application/AgentRuntimeStreamHandle.java`、`[MOD] AgentRuntimeStreamingPort.java`：异步流本地取消句柄。
- `[MOD] devpilot-agent/.../application/AgentRunApplicationService.java`：CurrentUser→project scope→`AGENT_PROPOSE`→状态校验→取消编排。
- `[MOD] devpilot-agent/.../application/AgentRunStreamCoordinator.java`：持有 run→handle，仲裁 cancel/terminal race，保证唯一 terminal 与 cleanup。
- `[MOD] devpilot-agent/.../application/AgentRunPersistenceService.java`、`persistence/mapper/AgentRunMapper.java`：短事务条件更新，race loser 返回 empty。
- `[NEW] devpilot-agent/.../application/AgentRunCancellationMetrics.java`：取消请求结果指标。
- `[MOD] devpilot-agent/.../api/AgentRunController.java`：新增 scoped Cancel API。
- `[MOD] devpilot-agent/.../application/AgentStreamEventType.java`、`sse/AgentRunEventHub.java`：发布 `run-cancelled` 并关闭 SSE terminal 连接。
- `[MOD] devpilot-agent/.../error/AgentRunErrorCode.java`：terminal conflict 与 cancel transport failure 的稳定 HTTP 错误。

## Java gRPC 与 Resilience

- `[MOD] devpilot-agent/pom.xml`：引入 BOM 管理的非 Reactive Spring Cloud CircuitBreaker Resilience4j starter 和 Bulkhead module。
- `[MOD] devpilot-agent/.../infrastructure/grpc/GrpcAgentRuntimeStreamingClient.java`：`ClientResponseObserver` 捕获 `ClientCallStreamObserver` 并返回 cancellable handle。
- `[NEW] devpilot-agent/.../infrastructure/grpc/GrpcAgentRuntimeCancellationClient.java`：短 deadline CancelRun adapter，隐藏 transport description。
- `[NEW] devpilot-agent/.../infrastructure/grpc/ResilientAgentRuntimeStreamingPort.java`：跨 async callback 的 CircuitBreaker/Bulkhead 一次性记账。
- `[NEW] devpilot-agent/.../infrastructure/grpc/AgentRuntimeResilienceMetrics.java`：Circuit/容量/活跃流指标。
- `[MOD] devpilot-agent/.../infrastructure/grpc/AgentGrpcConfiguration.java`、`AgentGrpcProperties.java`：装配 raw stream、cancel stub 与独立 deadline。
- `[NEW] devpilot-agent/.../config/AgentResilienceProperties.java`、`AgentResilienceConfiguration.java`：配置化 CLOSED/OPEN/HALF_OPEN 和 Semaphore Bulkhead。
- `[MOD] devpilot-boot/src/main/resources/application.yml`、`[MOD] .env.example`：安全默认值与环境变量清单。

线程边界：HTTP 线程只启动或取消；gRPC callback 线程推动投影；数据库条件更新决定 terminal winner。Circuit/Bulkhead permit 从建流前持续到 async terminal/error/cancel。

## Python Runtime

- `[NEW] agent-service/src/devpilot_agent_service/runtime/cancellation.py`：thread-safe ActiveRunRegistry、CancellationToken 和有界 terminal tombstone。
- `[MOD] agent-service/src/devpilot_agent_service/runtime/agent_loop.py`、`runtime/errors.py`：安全 checkpoint 与稳定 `RunCancelled`。
- `[MOD] agent-service/src/devpilot_agent_service/rpc/application.py`：向 AgentLoop 传递 token；fake delay 仅供 fault injection。
- `[MOD] agent-service/src/devpilot_agent_service/rpc/servicer.py`：注册 active run、拒绝 duplicate、CancelRun signal、恰好一个 RUN_CANCELLED；worker 真实退出后 cleanup。
- `[MOD] agent-service/src/devpilot_agent_service/rpc/server.py`：进程级共享 Registry 与 slow fake 配置。
- `[NEW] agent-service/src/devpilot_agent_service/rpc/circuit_breaker.py`：Python→Java Tool Gateway 的 CLOSED/OPEN/HALF_OPEN 状态机。
- `[MOD] agent-service/src/devpilot_agent_service/rpc/tool_gateway_client.py`：仅 transport deadline/unavailable 计失败，OPEN 不发 RPC。
- `[MOD] agent-service/src/devpilot_agent_service/model/providers/config.py`、`openai_compatible.py`：connect/read/overall timeout，保持 SDK `max_retries=0`。

安全边界：Python 只持有 run/request correlation，不接受 userId/role；权限仍由 Java 从 AgentRun 恢复。错误和日志不包含 secret、原始 Provider body 或 Tool payload。

## Tests

- `[MOD] devpilot-agent/src/test/...`：既有 API、状态投影、stream、配置和 contract 测试适配 CANCELLED。
- `[NEW] .../GrpcAgentRuntimeCancellationClientTest.java`：取消 request/response 与脱敏传输失败。
- `[NEW] .../ResilientAgentRuntimeStreamingPortTest.java`：async failure→OPEN、fast fail、capacity 与 cancel release。
- `[NEW] .../CrossLanguageAgentResilienceSmokeTest.java`：真实 Python process/TCP 故障注入，环境开关控制。
- `[NEW] agent-service/tests/test_cancellation.py`：duplicate、unknown、token、terminal cleanup。
- `[NEW] agent-service/tests/test_circuit_breaker.py`：OPEN、HALF_OPEN、恢复与 reopen。
- `[MOD] agent-service/tests/test_agent_loop.py`、`test_rpc_server.py`、`test_openai_compatible_model.py`：checkpoint、CancelRun contract 与 timeout。

## 阅读顺序

1. Contract、AgentRunStatus、FailureKind
2. Cancel API 与 AgentRunApplicationService
3. Python CancellationToken / ActiveRunRegistry / Servicer
4. AgentLoop checkpoints
5. Java stream handle 与 Coordinator race
6. Resilience configuration/decorator 与 metrics
7. Python Tool breaker 与 Provider timeout
8. 单元测试和 cross-language fault injection

