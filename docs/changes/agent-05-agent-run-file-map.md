# P0-05 AgentRun 与 HTTP API 文件地图

## 基线与范围

- 分支/基线：`agent` / `9011c14d0aa21bc5ef3bda898672adb7d6ef867f`。
- 起始工作树已有 `devpilot-agent/pom.xml` 的 protobuf generated source 注册改动，本次保留并在其上追加业务依赖。
- 范围：Java-owned AgentRun 投影、RUNNING→终态、分段事务、Project RBAC、同步 POST/GET、测试和文档。
- 未修改 proto/Python；未实现列表、Streaming/SSE、Cancel、Tool Gateway、Retry 或 Agent 写操作。

## 生产代码

| 标记/文件 | 职责与调用关系 | 事务、状态、权限与安全 |
| --- | --- | --- |
| [MOD] `devpilot-agent/pom.xml` | 增加 MyBatis、Spring Context/Tx/Web、Validation 直接依赖 | 临时 proto 依赖使用带构建时间戳的系统临时目录，避免 Windows Language Server 文件锁；无 Spring Cloud/LLM SDK |
| [MOD] `application/AgentRunStatus.java` | 增加 Java 投影初态 RUNNING | wire enum 仍只映射 Python 终态 |
| [NEW] `application/AgentRunApplicationService.java` | HTTP→身份/RBAC→Tx1→Runtime Port→Tx2 | 自身无事务；POST AGENT_PROPOSE、GET AGENT_READ；异常不泄密 |
| [NEW] `application/AgentRunPersistenceService.java` | 独立短事务 Bean | create/成功/失败分为 public 事务方法；条件终态更新 |
| [NEW] `application/AgentRunView.java` | 应用层只读投影 | API 不暴露 Entity |
| [NEW] `application/AgentRunFailureKind.java` | 持久化稳定失败 taxonomy | 不保存远端 description/body/stack |
| [NEW] `application/AgentRunIdentity.java` | requestId/runId 不可变值对象 | 不承载权限或状态 |
| [NEW] `application/AgentRunIdentityFactory.java` | Application Service 调用它生成 UUID | RPC 前生成；数据库唯一键兜底 |
| [NEW] `application/AgentRunTimeProvider.java` | UTC 业务时间 | 不依赖其他模块 Clock Bean |
| [NEW] `api/AgentRunController.java` | POST/GET scoped HTTP 入口 | Controller 不访问 Mapper、不放业务规则 |
| [NEW] `api/dto/StartAgentRunRequest.java` | POST 输入 DTO | 非空、最多 10000 字符；客户端不能提交身份/状态/version |
| [NEW] `api/dto/AgentRunResponse.java` | AgentRunView→HTTP data | 不暴露 Persistence Entity |
| [NEW] `error/AgentRunErrorCode.java` | 400/404/409 稳定错误 | 使用统一 GlobalExceptionHandler |
| [NEW] `persistence/entity/AgentRunEntity.java` | MyBatis 行映射 | 不作为 HTTP Response |
| [NEW] `persistence/mapper/AgentRunMapper.java` | scoped insert/select/terminal update | SQL 始终带 workspace/project；RUNNING+version 仲裁 |
| [NEW] `V14__add_agent_run_projection.sql` | 创建 `dp_agent_run` | FK、唯一键、CHECK、scope/time 索引 |

## 测试、装配与文档

| 标记/文件 | 证据 |
| --- | --- |
| [NEW] `devpilot-agent/.../AgentRunApplicationServiceTest.java` | 权限、调用顺序、成功/失败/Deadline/Unavailable/Protocol/未知异常 |
| [NEW] `devpilot-agent/.../AgentRunPersistenceServiceTest.java` | 初态、唯一冲突、不存在、单次终态、稳定 failureKind |
| [NEW] `devpilot-agent/.../AgentRunControllerTest.java` | Validation、统一 ApiResponse 与权限错误透传 |
| [NEW] `devpilot-boot/.../AgentRunHttpIntegrationTest.java` | V14/MySQL、MVC、RBAC、scope、事务外 RPC、失败可查询 |
| [MOD] `devpilot-boot/.../IsolatedPersistenceTestConfiguration.java` | 无数据库 Boot smoke 提供 AgentRunMapper mock |
| [MOD] `devpilot-boot/.../DevPilotApplicationTests.java` | 验证 AgentRunApplicationService 完成 Spring 装配 |
| [MOD] `.gitignore` | 忽略 Eclipse `bin/` 并精确放行第 5 章文档 |
| [NEW] `docs/agent/05-agent-run-business-projection.md` | 本章架构、事务、状态、RBAC、失败与限制 |
| [NEW] `docs/changes/agent-05-agent-run-file-map.md` | 本文件，逐文件记录职责和验证证据 |
| [MOD] `docs/database-design.md` | 把 AgentRun 从规划更新为 V14 当前结构 |
| [MOD] `docs/capability-coverage-and-roadmap.md` | 把 Agent L0 更新为真实 gRPC + HTTP 纵切 |
| [MOD] `README.md` | 增加 AgentRun POST/GET 与当前限制 |

## 验证记录

| 状态 | 命令/场景 | 实际结果 |
| --- | --- | --- |
| PASS | 变更前 `mvn -pl devpilot-agent -am test` | Agent 15 tests；14 passed，跨语言条件测试 skipped 1 |
| PASS | 变更后最终 `mvn -pl devpilot-agent -am test` | Agent 32 tests；31 passed，跨语言条件测试 skipped 1 |
| PASS | `mvn -pl devpilot-boot -am -DskipTests test-compile` | 11 模块 testCompile SUCCESS |
| ENV SKIP | 定向 `AgentRunHttpIntegrationTest` | 5 tests 因本机无可用 Docker daemon skipped；Maven SUCCESS |
| PASS | 架构规则与 Boot context 定向测试 | 8 tests passed；Agent 应用服务完成装配，无跨模块 Persistence/API→Mapper 违规 |
| PASS | `mvn -pl devpilot-agent -am clean verify` | 5 模块 SUCCESS；Agent 27 tests，26 passed、1 skipped |
| PASS | `mvn clean verify` | 11 模块 SUCCESS；共 376 tests，270 passed、106 环境条件 skipped |
| PASS | `docker compose config` | MySQL 8.4、Redis 7.4 配置解析成功 |
| PASS | Python pytest（未修改 Runtime 的回归） | 隔离依赖缓存运行，72 passed |
| BLOCKED | `python -m ruff check agent-service` | 全局环境无 Ruff；隔离下载 `ruff 0.16.4` 经 6 次重试仍因 PyPI timeout 失败 |

首次两次全量 clean 在 Windows protobuf 插件清理 workspace 内临时 proto 目录时被 VS Code Java Language Server
文件锁阻断。把该临时目录移到带 Maven build timestamp 的系统临时路径后，Agent clean verify 与全量 clean verify
均实际通过；没有停止或修改用户的 Language Server 进程。
