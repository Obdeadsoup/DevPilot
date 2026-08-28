# P0-07 文件地图：Read-only Tool Gateway

## 调用方向

```text
Java → Python: AgentRuntime.StreamRun
Python → Java: DevPilotToolGateway.ExecuteTool
```

第二条链只接受 service-authenticated、run-bound、allowlisted、read-only Tool。

## 文件地图

| 变更 | 文件/目录 | 职责与边界 | 主要验证 |
|---|---|---|---|
| [MOD] | `contracts/agent/v1/agent_runtime.proto` | 保留字段 1；加入 run/call/name/Struct、status/result/error | Java/Python generated contract |
| [GENERATED] | `agent-service/.../rpc/generated/agent_runtime_pb2*.py` | Python Stub；不含手工业务逻辑 | codegen + pytest |
| [MOD] | `agent-service/scripts/generate_grpc.py` | 加入 well-known proto include | 实际 codegen |
| [NEW] | `runtime/context.py` | 只含 runId/requestId，不含用户/scope/secret | RunContext/Loop tests |
| [MOD] | `tools/base.py`, `tools/registry.py`, `runtime/agent_loop.py` | run context/callId 进入 Tool，旧本地 Tool 兼容 | AgentLoop regression |
| [NEW] | `rpc/tool_gateway_client.py` | 长生命周期 Channel、metadata、deadline、Struct/callId/size/error校验 | client unit + TCP smoke |
| [NEW] | `tools/devpilot.py` | 三个 Remote Tool schema 与 1..20 参数限制 | remote Tool tests |
| [MOD] | `rpc/application.py`, `rpc/servicer.py`, `rpc/server.py` | StreamRun 传 RunContext；DeepSeek 装配真实 Registry/关闭 Channel | RPC/server tests |
| [MOD] | `devpilot-agent/pom.xml` | 单向增加 task Application Query 依赖 | Maven/ArchUnit |
| [NEW] | `application/AgentRunExecutionContext*` | runtime 内部最小权威上下文 Port/DTO | Application tests |
| [MOD] | `AgentRunPersistenceService/Mapper` | service-auth 后按全局 runId 恢复上下文 | persistence/module tests |
| [NEW] | `application/tool/*` | allowlist、run-bound delegation、三个 handler、结果大小策略 | service/handler tests |
| [NEW] | `config/AgentToolGrpc*` | Server 开关、loopback、线程/消息/secret 配置；toString 脱敏 | context/lifecycle tests |
| [NEW] | `infrastructure/toolgrpc/*` | interceptor、Struct mapper、gRPC service、metrics、Server lifecycle | real Netty tests |
| [MOD] | `ProjectService` | `getProjectForActor`，每次 PROJECT_READ | ProjectServiceTest |
| [MOD] | `ProjectActivityService` | `queryTimelineForActor`，每次 PROJECT_ACTIVITY_READ | ProjectActivityServiceTest |
| [MOD] | `TaskQueryService/TaskMapper` | explicit actor、TASK_READ、SQL 排除终态、limit≤20 | TaskQueryServiceTest |
| [MOD] | `application.yml`, `application-test.yml`, `.env.example` | 50052/key/deadline/message 配置；test 默认关闭 | Boot context/Compose |
| [MOD] | `LayerBoundaryArchitectureTest` | Gateway/handler 不得依赖 Mapper | ArchUnit |
| [NEW] | Java/Python Tool tests 与 cross-language smoke | auth/delegation/dispatch/size/callId/Loop/wire | Maven/pytest/E2E |
| [MOD] | `AgentRunHttpIntegrationTest` | Docker 可用时以真实 RUNNING Run/Project/Task/Activity 验证 DB 数据、scope 与撤权后重新 RBAC | Testcontainers integration |
| [MOD] | README、contract/agent-service README | 同步真实能力与限制 | 人工核对 |
| [NEW] | `docs/agent/07-read-only-tool-gateway.md` | 安全/调用链/线程/错误/边界解释 | 文件地图核对 |

## 关键 Diff 阅读顺序

1. updated ExecuteTool proto
2. Python `RunContext`
3. Python `JavaToolGatewayClient`
4. Python `tools/devpilot.py`
5. Java `AgentToolServiceAuthInterceptor`
6. Java `AgentToolGrpcServerLifecycle`
7. `AgentRunExecutionContextQuery`
8. `AgentToolApplicationService`
9. 三个 `*ToolHandler`
10. Project/Task/Activity explicit-actor Diff
11. tests
12. generated code（只看接口）
13. 本章设计文档

## 验证记录

| 状态 | 命令/场景 | 结果 |
|---|---|---|
| PASS | `agent-service/scripts/generate_grpc.py` | Python protobuf/gRPC codegen 实际执行，退出码 0 |
| PASS | `.venv/Scripts/python.exe -m pytest agent-service/tests` | 90 passed |
| PASS | `.venv/Scripts/python.exe -m ruff check agent-service` | All checks passed |
| PASS | `mvn -pl devpilot-agent -am test` | Agent 64：62 passed、2 conditional skipped；Project 39、Task 6 全过 |
| PASS | 显式开启 `CrossLanguageToolGatewaySmokeTest` | Java Netty Server 与独立 Python 进程通过真实 TCP/HTTP2；三个 wire Tool 与 FakeModel→Remote Tool→Final 主链通过 |
| ENV SKIP | `AgentRunHttpIntegrationTest.readOnlyToolsUsePersistedRunScopeRealDataAndFreshRbac` | 测试已编译；本机 Docker daemon 不可用，Testcontainers 跳过真实 MySQL 数据/RBAC 场景 |
| PASS | `mvn clean verify` | 11 modules BUILD SUCCESS；420 tests，311 passed、109 skipped（Docker 与两个显式 cross-language 门禁） |
| PASS | `docker compose config --quiet` | Compose 配置有效，退出码 0；该命令不要求 Docker daemon |
| PASS | `git diff --check` 与边界扫描 | 无 whitespace error；Tool Gateway/handler 不依赖 Mapper，contract/RunContext 不接受权威 user/scope |
| NOT RUN | DeepSeek + real Tool E2E | 本机未设置 `DEEPSEEK_API_KEY`，且 Docker/真实业务数据不可用；不以 Fake 结果冒充 |

其中跨语言 smoke 验证真实双进程 wire、service metadata、Struct、callId 和 AgentLoop；Java 的真实
Application Service/RBAC/SQL 由单元测试和 Docker 门禁集成测试覆盖。当前环境无法把两部分合并为真实 MySQL
端到端，因此明确记录 `ENV SKIP`，不宣称该场景已运行通过。
