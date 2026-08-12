# 第 16 节：后端测试矩阵

本矩阵以 Maven Surefire 的 `*Test` 命名约定和当前根 Reactor 为准。`mvn clean verify` 会运行下列测试；没有另设只在 IDE 中生效的 Profile，也不通过跳过测试维持构建。

| 层级 | 核心覆盖与代表测试 | MySQL | Redis | MockWebServer | 进入 verify / CI |
|---|---|---:|---:|---:|---:|
| Unit | Task 状态迁移、Retry/Failure Policy、签名、解析、Metrics、Audit 元数据、SSE Registry | 否 | 否 | 部分 GitHub Client 测试 | 是 |
| Architecture | `DevPilotModuleArchitectureTest`、`LayerBoundaryArchitectureTest`、`CrossModulePersistenceBoundaryTest` | 否 | 否 | 否 | 是 |
| Security | `AuthenticationSecurityIntegrationTest`、`ScopedRbacIntegrationTest`、Replay 授权单测 | 是 | 部分 | 否 | 是 |
| Integration | Webhook、Binding、Reconciliation、Delivery Recovery、Workspace/Project、Notification、Outbox、Replay/Audit、Observability | 是 | 部分 | 部分 | 是 |
| Testcontainers | boot 模块集成测试基类启动随机映射的 MySQL/Redis；Flyway 从 V1 完整迁移 | 是 | 是 | 否 | 是，需要 Docker |
| External HTTP Mock | `GitHubApiHttpExecutorTest`、两个 REST Client 测试、Snapshot Client 测试及相关集成场景 | 否/按场景 | 否 | 是 | 是 |
| Module Assembly | `ActuatorHealthEndpointTest`、`ObservabilityActuatorIntegrationTest`、`PrometheusEndpointIntegrationTest` 及 boot 上下文集成测试 | 是 | 是 | 否 | 是，需要 Docker |

## 模块覆盖

- `devpilot-framework`：统一响应、全局异常和 Correlation ID Filter。
- `devpilot-identity`：数据库用户加载、Token 生命周期、身份配置；Redis 集成在 boot 验证。
- `devpilot-project`：Workspace/Project 权限矩阵、生命周期、范围查询与乐观锁。
- `devpilot-github`：Webhook、API Client、分页/ETag/Rate Limit、Binding、Delivery/Sync 状态机、对账和 Metrics。
- `devpilot-task`：状态机与 Transactional Outbox 事件工厂；事务装配在 boot 验证。
- `devpilot-notification`：接收人、去重、提醒扫描、Outbox Handler 和 SSE。
- `devpilot-outbox`：Retry、Failure/Handler Registry、backlog snapshot 和 Metrics；状态机/事务在 boot 验证。
- `devpilot-audit`：Replay Policy、授权、序列、Correlation 与 Audit 元数据；端到端在 boot 验证。

## 脆弱性检查结论

- 没有发现只靠 IDE classpath 才能运行的测试；CI 从根 POM 执行完整 Reactor。
- GitHub HTTP 测试使用本地 MockWebServer，不访问真实 GitHub，也不要求 PAT。
- 测试源码未使用 `Thread.sleep`；异步断言使用 Awaitility。生产代码中的可注入 Sleeper 不属于测试等待。
- `application-test.yml`、`application-integration-test.yml` 与 `application-identity-integration-test.yml` 按各自依赖关闭 Delivery/Sync/Outbox、Backlog、Reminder、SSE heartbeat、Reconciliation 等 Scheduler，避免后台扫描跨测试上下文访问已停止容器。
- Testcontainers 使用随机映射端口；MockWebServer 也使用本地动态端口，未发现固定应用端口冲突。
- 每个集成测试自行准备/清理作用域数据，不依赖测试方法执行顺序。
- 日志断言与配置不输出 credential、Token、Webhook Secret 或原始私有 Payload；真实值由环境或 Testcontainers 动态属性提供。

## 本地和 CI 约束

Docker 不可用时，核心 Testcontainers 集成测试不能被视为通过，禁止用 `-DskipTests` 代替。GitHub Actions 的 Ubuntu Runner 提供 Docker，并执行与本地相同的 `mvn -B -ntp clean verify`。JMeter 基线是人工验收，不进入普通 PR Gate。

## 前端现状（不属于本轮后端 Gate）

2026-08-12 实际执行 `npm ci`、`npm run typecheck`、`npm run build` 均通过。只记录两个下一轮缺口：npm audit 报告 1 个 high severity vulnerability；Vite 报告主 JS chunk 约 1.33 MB、超过 500 kB 提示线。本轮没有升级前端依赖或拆包。
