# P0-04 Java ↔ Python gRPC 文件地图

## 基线与版本

- 分支/实际基线：`agent` / `6180780928b83b9dfbde898285403483985dfef8`，与指令基线一致。
- 起始工作树：clean。
- Java：gRPC `1.83.1`、protobuf/protoc `4.35.1`、protobuf-maven-plugin `0.6.1`、
  os-maven-plugin `1.7.1`。
- Python 隔离环境实际安装：grpcio/grpcio-tools `1.83.0`、protobuf `7.36.0`、OpenAI SDK `3.3.1`。
- 范围：Unary StartRun、双端 codegen、Python Server、Java Client、Deadline/Status、双进程 smoke；无数据库/HTTP API。

## 契约、构建与配置

| 标记/文件 | 职责、所属模块、调用关系与关键内容 | RPC、生命周期/线程、错误与安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [MOD] `contracts/agent/v1/agent_runtime.proto` | 唯一 IDL；protoc 与 grpcio-tools 读取；StartRun 增加 run_id/user_input/final_output/status | wire source of truth；字段号只追加；不含 Secret/Provider DTO | 双端 generated contract tests、E2E |
| [MOD] `contracts/agent/v1/README.md` | 契约目录说明；开发者/codegen 调用 | 明确未实现 RPC 为 UNIMPLEMENTED；身份字段不授权 | 文档审查 |
| [MOD] `devpilot-agent/pom.xml` | Java gRPC 依赖与 Maven codegen；直接读取根 proto | generate-sources 生成 target 文件；不复制/手改 proto；无 Spring Cloud | `mvn ... generate-sources/test` |
| [MOD] `agent-service/pyproject.toml` | Python grpcio/protobuf 运行依赖、grpcio-tools dev 依赖；Ruff 精确排除 generated | codegen 不在 Server 启动时执行；generated 不人工格式化 | 隔离安装、pytest、Ruff |
| [MOD] `devpilot-boot/src/main/resources/application.yml` | Java Client endpoint/deadline/plaintext 安全默认；Spring Boot 读取 | Channel 进程级；每次 RPC Deadline；Secret 不在配置 | Boot context、configuration test |
| [MOD] `.env.example` | 双端本地环境变量名和非敏感默认值 | Key 仍为空；fake 明确仅联调；本地 plaintext | 配置测试、E2E |
| [MOD] `.gitignore` | 精确放行第 4 章两份 docs | 不泛化追踪其余本地 docs；继续忽略 `.env` | `git check-ignore/status` |
| [MOD] `agent-service/README.md` | Python Server/codegen/smoke 操作入口 | 标明 insecure/local、UNIMPLEMENTED 与无业务数据库 | 文档命令实跑 |

## Python 手工代码与测试

| 标记/文件 | 职责、所属模块、调用方→被调用方、关键类/方法 | RPC、生命周期/线程、错误与安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [NEW] `agent-service/scripts/generate_grpc.py` | build 工具；开发者→grpc_tools.protoc；`main()` 定位根 proto 并生成 Python Stub | 只在开发/build 执行；修正包内相对 import；失败立即退出 | 实际 codegen、generated import test |
| [NEW] `rpc/generated/__init__.py` | generated package 标记；Python import 系统调用 | 不承载 DTO 之外的逻辑；无状态/Secret | generated contract test |
| [NEW] `rpc/application.py` | RPC 应用门面；Servicer→AgentRuntimeApplication→AgentLoop；含 DeterministicFakeModel | 无 protobuf DTO；Loop 状态为 run-local；fake 无网络/数据库 | servicer/server tests、E2E |
| [NEW] `rpc/servicer.py` | Python 入站 Adapter；generated Server→AgentRuntimeServicer.StartRun→应用门面 | Unary；gRPC worker 线程执行同步 Loop；空字段 INVALID_ARGUMENT，内部失败脱敏 INTERNAL | `test_rpc_servicer.py` |
| [NEW] `rpc/server.py` | Server 配置/组装/进程入口；`python -m ...server`→grpc.Server/Servicer | 8 worker；进程级 Server；fake/deepseek 显式模式；当前 insecure、无 auth | `test_rpc_server.py`、E2E |
| [NEW] `tests/test_generated_grpc_contract.py` | 验证 generated 字段、Service/Stub import | 无网络；防止 codegen/import 漂移 | 文件自身 |
| [NEW] `tests/test_rpc_servicer.py` | 直接验证 request→Loop→response 与 Status | Fake context 捕获 abort；断言错误描述不泄漏底层内容 | 文件自身 |
| [NEW] `tests/test_rpc_server.py` | 配置、fake、真实 TCP Server bootstrap、保留 RPC 状态 | 临时 loopback 端口；测试结束 stop Server；Stream/Cancel/ToolGateway 稳定返回 UNIMPLEMENTED；无 LLM | 文件自身 |

## Java Application Core 与 gRPC Infrastructure

| 标记/文件 | 职责、所属模块、调用方→被调用方、关键类/方法 | RPC、生命周期/线程、错误与安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [NEW] `application/AgentRunCommand.java` | Core command；未来 Application Service→Port；校验 request/run/input | 无 protobuf/gRPC；不可变 record；identity 不提升权限 | Client mapping/E2E |
| [NEW] `application/AgentRunStatus.java` | Core 同步业务状态 | 与 protobuf enum 隔离；无状态 | Client mapping |
| [NEW] `application/AgentRunResult.java` | Core result；Port→未来 Application Service | 无 generated DTO；校验 run id/status | Client mapping/E2E |
| [NEW] `application/AgentRuntimePort.java` | Java 出站 Port；Application→Adapter；`run()` | 不依赖 Stub/Channel；同步调用契约 | Client/E2E |
| [NEW] `infrastructure/grpc/AgentRuntimeFailureKind.java` | Java 稳定传输/协议错误 taxonomy | gRPC Status 不扩散进 Core；无敏感描述 | Status mapping |
| [NEW] `infrastructure/grpc/AgentRuntimeClientException.java` | Adapter 脱敏异常 | cause 保留本地 Status；公开 message 只有 kind | Status mapping |
| [NEW] `infrastructure/grpc/AgentGrpcProperties.java` | Spring endpoint/deadline/transport 配置 | Deadline 至少 1ms；支持 plaintext/TLS；无凭据 | Properties/config tests |
| [NEW] `infrastructure/grpc/AgentGrpcChannel.java` | ManagedChannel 生命周期；Configuration/Stub 调用 | 进程级复用、线程安全 Channel；graceful 5s 后 force；本地 plaintext 警告 | Channel/config lifecycle tests |
| [NEW] `infrastructure/grpc/GrpcAgentRuntimeClient.java` | Port Adapter；Command→proto→BlockingStub→Result | 每次显式 Deadline、无 Retry；Status/协议分类；校验回传 run id | Client tests、E2E |
| [NEW] `infrastructure/grpc/AgentGrpcConfiguration.java` | Spring 装配；创建一个 Channel、Stub、Port Bean | Context 管理 close；多次 RPC 复用；不主动连网直到调用 | Configuration/Boot tests |

以上 Java 路径前缀均为：
`devpilot-agent/src/main/java/com/obdeadsoup/devpilot/agent/`。

## Java 测试与真实跨语言入口

| 标记/文件 | 职责、所属模块、调用关系与关键断言 | RPC、生命周期/线程、错误与安全边界 | 对应测试 |
| --- | --- | --- | --- |
| [NEW] `AgentGrpcChannelTest.java` | Mockito 验证 graceful/force shutdown | 无网络；精确验证 close | 文件自身 |
| [NEW] `AgentGrpcConfigurationTest.java` | ApplicationContextRunner 验证单 Channel/Stub/Port 与销毁 | Context 关闭后 Channel shutdown | 文件自身 |
| [NEW] `AgentGrpcPropertiesTest.java` | 验证 Deadline 与两种 transport mode | 无网络/Secret | 文件自身 |
| [NEW] `GeneratedContractTest.java` | Java generated request 与 full method name | 无网络；防 codegen 漂移 | 文件自身 |
| [NEW] `GrpcAgentRuntimeClientTest.java` | Mockito Stub 验证 mapping、Deadline、Status、protocol | 不启动 Server；错误描述脱敏 | 文件自身 |
| [NEW] `CrossLanguageGrpcSmokeTest.java` | 独立 Java process→localhost Python process；断言 run/final/status | 环境开关默认 skip；只连真实 TCP，不启动 Java in-process Server | 实际双进程 E2E |

以上测试路径前缀均为：
`devpilot-agent/src/test/java/com/obdeadsoup/devpilot/agent/infrastructure/grpc/`。

## Generated 文件

来源统一为 `contracts/agent/v1/agent_runtime.proto`。Python 重建命令：
`python agent-service/scripts/generate_grpc.py`。Java 重建命令：
`mvn -pl devpilot-agent -am generate-sources`。生成文件不得手工修改、不得放业务规则或 Secret。

| 标记/文件 | 职责/调用关系 | RPC/生命周期/错误/安全 | 测试 |
| --- | --- | --- | --- |
| [GENERATED] `rpc/generated/agent_runtime_pb2.py` | Python message/enum descriptor；Servicer/tests import | wire serialization；无手工状态 | Python contract/E2E |
| [GENERATED] `rpc/generated/agent_runtime_pb2_grpc.py` | Python Stub/Servicer 注册骨架；server/servicer import | generated 只提供骨架；手工 Servicer 显式拒绝保留 RPC | Python contract/E2E |
| [GENERATED] `target/.../AgentRuntimeGrpc.java` | Java AgentRuntime Stub/method descriptors；Client import | Blocking/async/future API；target 可重建 | Java contract/client/E2E |
| [GENERATED] `target/.../DevPilotToolGatewayGrpc.java` | 反向 ToolGateway Stub 骨架 | 本章不实现/调用 | descriptor codegen 间接覆盖 |
| [GENERATED] `target/.../AgentRuntimeOuterClass.java` | Java file descriptor holder | 无业务状态 | Java contract |
| [GENERATED] `target/.../RunStatus.java` | Java wire enum | Adapter 转内部 enum | Client test |
| [GENERATED] `target/.../StartRunRequest.java` | Java request message | 只在 Adapter；不可进入 Core | Client/contract/E2E |
| [GENERATED] `target/.../StartRunRequestOrBuilder.java` | request builder interface | generated compile-time API | Maven compile |
| [GENERATED] `target/.../StartRunResponse.java` | Java response message | 只在 Adapter；协议字段受校验 | Client/contract/E2E |
| [GENERATED] `target/.../StartRunResponseOrBuilder.java` | response builder interface | generated compile-time API | Maven compile |
| [GENERATED] `target/.../StreamRunRequest.java` | 未来 Stream request message | RPC 保持 UNIMPLEMENTED | Maven compile |
| [GENERATED] `target/.../StreamRunRequestOrBuilder.java` | Stream request builder interface | 不调用 | Maven compile |
| [GENERATED] `target/.../AgentEvent.java` | 未来 Stream event message | 当前不生成假事件 | Maven compile |
| [GENERATED] `target/.../AgentEventOrBuilder.java` | event builder interface | 不调用 | Maven compile |
| [GENERATED] `target/.../CancelRunRequest.java` | 未来 Cancel request | RPC 保持 UNIMPLEMENTED | Maven compile |
| [GENERATED] `target/.../CancelRunRequestOrBuilder.java` | Cancel request builder interface | 不调用 | Maven compile |
| [GENERATED] `target/.../CancelRunResponse.java` | 未来 Cancel response | 不伪造 accepted | Maven compile |
| [GENERATED] `target/.../CancelRunResponseOrBuilder.java` | Cancel response builder interface | 不调用 | Maven compile |
| [GENERATED] `target/.../ExecuteToolRequest.java` | 未来 ToolGateway request | Python 不调用 Java/数据库 | Maven compile |
| [GENERATED] `target/.../ExecuteToolRequestOrBuilder.java` | Tool request builder interface | 不调用 | Maven compile |
| [GENERATED] `target/.../ExecuteToolResponse.java` | 未来 ToolGateway response | 不调用 | Maven compile |
| [GENERATED] `target/.../ExecuteToolResponseOrBuilder.java` | Tool response builder interface | 不调用 | Maven compile |

Python generated 路径位于 `agent-service/src/devpilot_agent_service/`；Java generated 路径位于
`devpilot-agent/target/generated-sources/protobuf/{java,grpc-java}/com/obdeadsoup/devpilot/agent/contract/v1/`，
后者属于 Maven target，不提交版本库。

## 文档文件

| 标记/文件 | 职责与边界 | 测试/证据 |
| --- | --- | --- |
| [NEW] `docs/agent/04-java-python-grpc.md` | RPC/IDL/codegen/Channel/Stub/Server/Deadline/Status/E2E 学习说明；不宣称未实现能力 | 与代码和实跑命令互校 |
| [NEW] `docs/changes/agent-04-grpc-file-map.md` | 本文件；逐文件记录职责、RPC、生命周期、错误、安全与测试 | 最终验证表 |

## 调用链

```text
agent_runtime.proto
├→ protoc + grpc-java plugin → Java generated Stub
└→ grpcio-tools → Python generated Servicer
```

```text
Java caller → AgentRuntimePort → GrpcAgentRuntimeClient → Java Stub → ManagedChannel
→ HTTP/2 + Protobuf → Python Server → AgentRuntimeServicer → AgentLoop → Model → Final → Java
```

## 验证记录

| 状态 | 命令/场景 | 实际结果 |
| --- | --- | --- |
| PASS | `python agent-service/scripts/generate_grpc.py` | 生成 pb2/pb2_grpc，包内相对 import 正常 |
| PASS | `mvn -pl devpilot-agent -am generate-sources` | protoc 与 grpc-java plugin 各生成 1 个 proto |
| PASS | Python pytest（最终验证） | 72 passed；含真实 loopback Server 与 Stream/Cancel/ToolGateway UNIMPLEMENTED 断言 |
| PASS | Python Ruff（最终验证） | All checks passed |
| PASS | Java Agent 测试（全量 Maven 中） | 15 tests，14 passed，常规运行下 E2E 入口 skipped 1 |
| PASS | 真实跨语言 smoke | Python PID 151296 + 独立 Java Surefire；1 passed，0 skipped；进程已停止 |
| PASS | `mvn clean verify` | 11 个 Reactor 模块全部 SUCCESS；Agent 15 tests；Boot 112 tests 中 100 个 Testcontainers 测试因本机 Docker daemon 不可用而 skipped |
| PASS | `docker compose config` | MySQL 8.4 与 Redis 7.4 配置解析成功 |
| PASS | Java Core/范围扫描 | application core 无 gRPC/protobuf import；Agent 新代码无 Mapper/DB/HTTP Controller/Spring Cloud |
| NOT RUN | 可选真实 DeepSeek E2E | 当前环境没有 `DEEPSEEK_API_KEY`；未以 fake 结果冒充 Provider 验证 |
