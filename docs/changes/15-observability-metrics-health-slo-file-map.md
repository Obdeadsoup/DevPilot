# 第 15 节变更文件地图：Observability、Metrics、Health 与 SLO

## 1. 完整文件列表

### [NEW]

| 文件 | 模块/分层/职责 |
|---|---|
| `devpilot-framework/../correlation/`<br>`CorrelationIdPolicy.java` | framework；校验或生成安全 Correlation ID |
| `devpilot-framework/../correlation/`<br>`CorrelationIdAccessor.java` | framework；有限 MDC 上下文读写与作用域恢复 |
| `devpilot-framework/../correlation/`<br>`CorrelationIdFilter.java` | HTTP 基础设施；请求/响应 Header 与 MDC 生命周期 |
| `devpilot-framework/../correlation/`<br>`CorrelationIdTaskDecorator.java` | async 基础设施；只传播 correlationId，durable 边界创建新值 |
| `devpilot-framework/src/test/../correlation/`<br>`CorrelationIdFilterTest.java` | Filter、并发、异常清理和 TaskDecorator 测试 |
| `devpilot-github/../application/`<br>`GitHubDeliveryMetrics.java` | GitHub Delivery Timer/DEAD Counter 门面 |
| `devpilot-github/../application/`<br>`GitHubSyncMetrics.java` | GitHub Sync Timer/DEAD Counter 门面 |
| `devpilot-github/../config/`<br>`GitHubBacklogProperties.java` | GitHub backlog 刷新间隔、stale 阈值配置 |
| `devpilot-github/../persistence/entity/`<br>`GitHubDeliveryBacklogQuery.java` | Delivery 聚合查询结果 DTO |
| `devpilot-github/../persistence/entity/`<br>`GitHubSyncBacklogQuery.java` | Sync 聚合查询结果 DTO |
| `devpilot-github/../persistence/mapper/`<br>`GitHubBacklogMapper.java` | 模块内 Delivery/Sync 有界聚合 SQL |
| `devpilot-github/../application/`<br>`GitHubBacklogSnapshot.java` | immutable GitHub backlog 快照 |
| `devpilot-github/../application/`<br>`GitHubBacklogSnapshotService.java` | 刷新并保留 last-good snapshot |
| `devpilot-github/../application/`<br>`GitHubBacklogRefreshScheduler.java` | 配置驱动的周期刷新入口 |
| `devpilot-github/../application/`<br>`GitHubBacklogMetricsBinder.java` | Gauge 仅从内存快照读取 |
| `devpilot-github/src/test/../application/`<br>`GitHubObservabilityMetricsTest.java` | Delivery/Sync 指标名、结果和低基数测试 |
| `devpilot-github/src/test/../application/`<br>`GitHubBacklogSnapshotServiceTest.java` | last-good、age、stale 和空快照测试 |
| `devpilot-outbox/../application/`<br>`OutboxMetrics.java` | Outbox Counter/Timer/Replay 指标门面 |
| `devpilot-outbox/../config/`<br>`OutboxBacklogProperties.java` | Outbox backlog 刷新配置 |
| `devpilot-outbox/../persistence/entity/`<br>`OutboxBacklogQuery.java` | Outbox 聚合查询结果 DTO |
| `devpilot-outbox/../persistence/mapper/`<br>`OutboxBacklogMapper.java` | 模块内 Outbox 有界聚合 SQL |
| `devpilot-outbox/../application/`<br>`OutboxBacklogSnapshot.java` | immutable Outbox backlog 快照 |
| `devpilot-outbox/../application/`<br>`OutboxBacklogSnapshotService.java` | 刷新并保留 last-good snapshot |
| `devpilot-outbox/../application/`<br>`OutboxBacklogRefreshScheduler.java` | 配置驱动的周期刷新入口 |
| `devpilot-outbox/../application/`<br>`OutboxBacklogMetricsBinder.java` | Outbox Gauge 内存绑定器 |
| `devpilot-outbox/src/test/../application/`<br>`OutboxObservabilityMetricsTest.java` | Outbox 指标和 tag 测试 |
| `devpilot-outbox/src/test/../application/`<br>`OutboxBacklogSnapshotServiceTest.java` | Outbox snapshot 失败保留和 age 测试 |
| `devpilot-notification/../application/`<br>`NotificationMetrics.java` | Notification/SSE bounded metrics 门面 |
| `devpilot-notification/src/test/../application/`<br>`NotificationMetricsTest.java` | Notification/SSE 指标安全测试 |
| `devpilot-audit/../application/`<br>`AuditReplayMetrics.java` | Outbox/GitHub Sync Replay 结果指标 |
| `devpilot-audit/src/test/../application/`<br>`AuditCommandFactoryCorrelationTest.java` | HTTP Audit 保存 correlationId 测试 |
| `devpilot-audit/src/test/../application/`<br>`AuditReplayMetricsTest.java` | Replay metric 低基数测试 |
| `devpilot-boot/../config/`<br>`ObservabilityConfiguration.java` | 全局 MeterFilter 高基数 tag 防线 |
| `devpilot-boot/src/main/resources/`<br>`application-observability.yml` | 受控 observability profile 的 endpoint/log 配置 |
| `devpilot-boot/src/test/../`<br>`ObservabilityActuatorIntegrationTest.java` | 默认 Health 与敏感 endpoint 边界测试 |
| `devpilot-boot/src/test/../`<br>`PrometheusEndpointIntegrationTest.java` | observability profile 真实 HTTP scrape 测试 |
| `devpilot-boot/src/test/../config/`<br>`ObservabilityConfigurationTest.java` | 高基数 MeterFilter 测试 |
| `devpilot-boot/src/test/../github/`<br>`BacklogSnapshotIntegrationTest.java` | MySQL/Redis Testcontainers backlog SQL 集成测试 |
| `ops/prometheus/`<br>`prometheus.yml` | 本地 Prometheus scrape 示例，不代表生产部署 |
| `docs/learning/`<br>`15-observability-metrics-health-slo.md` | 本节学习文档、PromQL 与故障演练 |
| `docs/changes/`<br>`15-observability-metrics-health-slo-file-map.md` | 本文件 |

### [MOD]

| 文件 | 变更职责 |
|---|---|
| `.gitignore` | 放行第 15 节文档与 Prometheus 示例 |
| `README.md` | 准确声明已实现能力及未实现边界 |
| `docs/architecture.md` | 补 Correlation、Metrics、Health 架构 |
| `docs/database-design.md` | 说明本节无 schema/Flyway 变更及索引复用 |
| `docs/capability-coverage-and-roadmap.md` | 更新 Observability 完成度与后续边界 |
| `devpilot-framework/pom.xml` | 为独立 framework 编译 Filter 引入 Servlet API |
| `devpilot-boot/pom.xml` | 增加 Boot BOM 管理的 Prometheus Registry |
| `devpilot-audit/pom.xml` | Audit 模块增加 Micrometer API |
| `devpilot-boot/src/main/resources/`<br>`application.yml` | 默认只暴露 health；定义 probes、health groups、backlog 配置 |
| `devpilot-boot/src/main/resources/`<br>`application-local.yml` | local 显式开放 health/metrics/prometheus 与日志 pattern |
| `devpilot-boot/src/test/resources/`<br>`application-test.yml` | 测试禁用 scheduler，隔离 readiness |
| `devpilot-boot/src/test/resources/`<br>`application-integration-test.yml` | 集成测试禁用 scheduler |
| `devpilot-boot/src/test/resources/`<br>`application-identity-integration-test.yml` | readiness 集成测试启用 Redis contributor |
| `devpilot-boot/../config/`<br>`SecurityConfiguration.java` | probes 匿名；metrics/prometheus 仅 local/observability 匿名 |
| `devpilot-boot/src/test/../`<br>`DevPilotApplicationTests.java` | 装配 Correlation、snapshot 并验证 test scheduler 关闭 |
| `devpilot-boot/src/test/../`<br>`IsolatedPersistenceTestConfiguration.java` | 隔离测试补 backlog Mapper mock |
| `devpilot-boot/src/test/../identity/`<br>`AuthenticationSecurityIntegrationTest.java` | DB/Redis readiness 与 liveness 边界测试 |
| `devpilot-github/../application/`<br>`GitHubDeliveryWorker.java` | 记录 Delivery processing Timer |
| `devpilot-github/../application/`<br>`GitHubDeliveryStateService.java` | 记录历史 DEAD transition Counter |
| `devpilot-github/../application/`<br>`GitHubSyncRunStateService.java` | 记录 Sync Timer/DEAD transition |
| `devpilot-github/../application/`<br>`GitHubWebhookActionMetrics.java` | 统一 devpilot 名称与 description |
| `devpilot-github/../application/client/`<br>`GitHubApiMetrics.java` | 统一 devpilot 名称、failure_type 与 description |
| `devpilot-github/../config/`<br>`GitHubIntegrationConfiguration.java` | GitHub executor 接入有限 MDC TaskDecorator |
| `devpilot-github/src/test/../application/`<br>`GitHubSyncRunStateServiceTest.java` | 适配 Metrics 依赖并校验结果 |
| `devpilot-github/src/test/../application/client/`<br>`GitHubApiHttpExecutorTest.java` | 适配标准化 metric 名称 |
| `devpilot-github/src/test/../config/`<br>`GitHubIntegrationPropertiesTest.java` | 配置切片显式装配 Correlation 组件，覆盖 executor 依赖 |
| `devpilot-outbox/../application/`<br>`DatabaseOutboxEventPublisher.java` | published/deduplicated Counter |
| `devpilot-outbox/../application/`<br>`OutboxWorker.java` | processing Timer 与 durable 新 correlationId |
| `devpilot-outbox/../application/`<br>`OutboxStateService.java` | processed/retry/dead 指标 |
| `devpilot-outbox/../config/`<br>`OutboxConfiguration.java` | Outbox executor 使用 durable TaskDecorator |
| `devpilot-notification/../application/`<br>`NotificationApplicationService.java` | created/deduplicated 指标 |
| `devpilot-notification/../application/`<br>`TaskNotificationOutboxHandler.java` | Handler Timer，区分业务处理与 SSE send |
| `devpilot-notification/../config/`<br>`TaskNotificationOutboxConfiguration.java` | 注入 NotificationMetrics |
| `devpilot-notification/../sse/`<br>`NotificationSseRegistry.java` | active connection Gauge 与 send result |
| `devpilot-notification/src/test/../application/`<br>`TaskNotificationOutboxHandlerTest.java` | 适配 Metrics 并验证处理 |
| `devpilot-notification/src/test/../sse/`<br>`NotificationSseRegistryTest.java` | SSE connection/send metric 回归 |
| `devpilot-audit/../application/`<br>`AuditCommandFactory.java` | 新 HTTP Audit 写入当前 correlationId |
| `devpilot-audit/../application/`<br>`GitHubSyncReplayApplicationService.java` | GitHub Sync replay created/denied/failed 指标 |
| `devpilot-audit/../application/`<br>`OutboxReplayApplicationService.java` | Outbox replay created/denied/failed 指标 |

### [DEL]

无。

## 2. 真实调用链

```text
HTTP → CorrelationIdFilter → CorrelationIdPolicy → CorrelationIdAccessor(MDC)
    → Security/Controller/Application Service → AuditCommandFactory
    → response X-Correlation-ID → finally 恢复/清理 MDC

HTTP thread → CorrelationIdTaskDecorator → GitHub bounded executor → Worker → finally 恢复线程原值
Scheduler/Outbox durable boundary → decorateFresh → 独立 correlationId → OutboxWorker/Handler

GitHubDeliveryWorker → GitHubDeliveryMetrics Timer
GitHubDeliveryStateService → GitHubDeliveryMetrics historical DEAD Counter
GitHubSyncRunStateService → GitHubSyncMetrics Timer / historical DEAD Counter
Outbox publisher/worker/state → OutboxMetrics Counter / Timer
Notification service/handler/SSE registry → NotificationMetrics Counter / Timer / Gauge
Replay application service → AuditReplayMetrics

RefreshScheduler → BacklogSnapshotService → 模块内 BacklogMapper 聚合 SQL
    → AtomicReference(last-good snapshot) → BacklogMetricsBinder Gauge callback

Actuator HealthEndpoint → livenessState
Actuator HealthEndpoint → readinessState + db + redis

Prometheus → /actuator/prometheus → Micrometer PrometheusMeterRegistry
    → 自动 JVM/HTTP/system 指标 + devpilot 自定义指标
```

MDC 只解决同一进程线程切换的日志关联，不是权限上下文，也不是 OpenTelemetry Trace。Outbox/Scheduler
属于 durable boundary，不能把原 HTTP ThreadLocal 当可靠载体。

## 3. Metric name 与 bounded tag

| Micrometer 名称 | 类型 | tags/值域 | SLI 用途 |
|---|---|---|---|
| `devpilot.github.delivery.processing` | Timer | `event_type` 白名单，`result=success/retry_wait/dead` | Delivery 耗时、成功/失败率 |
| `devpilot.github.delivery.dead.transitions` | Counter | `event_type` 白名单 | historical DEAD 发生率 |
| `devpilot.github.delivery.backlog` | Gauge | `status=received/retry_wait_due/processing/open_dead` | 当前 Delivery backlog |
| `devpilot.github.delivery.oldest.ready.age` | Gauge | 无 | 最老 ready 等待时间 |
| `devpilot.github.sync.run.duration` | Timer | `resource_type/result/trigger_type` bounded | Sync 耗时和结果率 |
| `devpilot.github.sync.dead.transitions` | Counter | `resource_type` bounded | historical DEAD 发生率 |
| `devpilot.github.sync.backlog` | Gauge | `status=pending/retry_wait_due/running/open_dead` | 当前 Sync backlog |
| `devpilot.github.sync.oldest.ready.age` | Gauge | 无 | 最老 ready run |
| `devpilot.outbox.processing` | Timer | `event_type/result` bounded | Outbox 处理耗时/结果 |
| `devpilot.outbox.published/processed/retry.wait/dead.transitions` | Counter | bounded enum tag | 吞吐、重试与历史 DEAD |
| `devpilot.outbox.backlog` | Gauge | `status=pending/retry_wait_due/processing/open_dead` | 当前 Outbox backlog |
| `devpilot.outbox.oldest.ready.age` | Gauge | 无 | 最老 ready event |
| `devpilot.notification.created/deduplicated` | Counter | `notification_type/source_type` enum | 创建/去重率 |
| `devpilot.notification.outbox.handler` | Timer | `event_type/result` bounded | Handler 耗时/结果 |
| `devpilot.notification.sse.connections` | Gauge | 无 | 当前 JVM SSE 连接数 |
| `devpilot.notification.sse.send` | Counter | `result=success/failed` | 推送结果；不等于通知持久化结果 |
| `devpilot.audit.replay` | Counter | `resource_type=outbox/github_sync`，`result=created/denied/failed` | 人工 Replay 结果 |
| `devpilot.*.backlog.snapshot.age/stale/refresh.failures` | Gauge/Counter | 无 | 快照新鲜度和刷新故障 |

禁止 `userId/workspaceId/projectId/taskId/repositoryId/deliveryId/outboxId/runId/requestId/correlationId`
以及 URL、登录名、异常消息等高基数或敏感值。业务门面只接收 enum/白名单值，全局 `MeterFilter`
再拒绝明显危险 tag key；指标测试验证不同业务实体不会新增 Meter。

## 4. Backlog SQL 与状态语义

`GitHubBacklogMapper` 仅查询 GitHub 自己的 Delivery/Sync 表，`OutboxBacklogMapper` 仅查询 Outbox 表。
每类使用单次条件聚合，区分 `next_retry_at <= now` 的 due retry 与 future retry，并通过已有状态/时间索引缩小范围。

- READY：Delivery `RECEIVED`、Sync `PENDING`、Outbox `PENDING`，再加各自到期的 `RETRY_WAIT`。
- stale processing/running：超过配置阈值，单独统计，不混入 ready age。
- historical DEAD：状态迁入 DEAD 的 Counter/不可变历史记录。
- open DEAD：DEAD 且不存在仍开放或已经成功的子 Replay；原 DEAD 行不被覆盖。
- empty age：`0`；从未成功刷新时 snapshot age 为 `+Inf` 且 stale 为 `1`。
- 刷新失败：Counter 增加，Gauge 继续读取 last-good snapshot，不让 scrape 执行 SQL或抛异常。

本节没有新增表、列、索引或 Flyway migration。

## 5. Health、Security 与 SLO 数据来源

- liveness：只含 `livenessState`，不探测 MySQL、Redis、GitHub API、backlog 或 DEAD。
- readiness：`readinessState + db + redis`；DevPilot 的认证和核心业务依赖两者。
- `/actuator/health`、probes、`/livez`、`/readyz` 可匿名；details 始终 `never`。
- metrics/prometheus 仅 local/observability profile 暴露并匿名，默认 profile 不公开；这只适用于本地或受控运维网络。
- Webhook、Sync、Outbox 的 Timer 及 DEAD/Backlog/Age 指标构成候选 SLI；Notification Counter 和 SSE Gauge
  构成通知可用性观测。SLO 只能在压测与故障演练后确定；当前无对外 SLA。

## 6. 关键 Diff 导读与测试

1. 先读三个 POM、`application.yml`、local/observability profile，理解 Registry 和暴露边界。
2. 读 `CorrelationIdFilter`、Policy、Accessor、TaskDecorator，随后看 `CorrelationIdFilterTest`。
3. 读 `ObservabilityConfiguration`，再看各模块 `*Metrics` 门面和 SimpleMeterRegistry 测试。
4. 读 GitHub/Outbox `BacklogMapper → SnapshotService → MetricsBinder`，机械 DTO/properties 可暂时跳过。
5. 读 `SecurityConfiguration` 与 Actuator/Prometheus 集成测试。
6. 读 `BacklogSnapshotIntegrationTest`，核对 due/future retry、stale、oldest age、open DEAD SQL。
7. 最后读 `ops/prometheus/prometheus.yml` 和学习文档中的 PromQL/演练。

对应测试重点：

- `CorrelationIdFilterTest`：Header 规则、异常清理、并发、async/durable context。
- `*ObservabilityMetricsTest`、`NotificationMetricsTest`、`AuditReplayMetricsTest`：名称、类型、结果、低基数。
- `*BacklogSnapshotServiceTest`：last-good、snapshot age/stale、空值。
- `BacklogSnapshotIntegrationTest`：真实 MySQL SQL 和 historical/open DEAD。
- `ObservabilityActuatorIntegrationTest`、`AuthenticationSecurityIntegrationTest`：Health group 与安全边界。
- `PrometheusEndpointIntegrationTest`：真实 HTTP text scrape、自动/自定义指标及敏感值排除。

推荐阅读顺序中的 DTO、properties 和普通构造注入代码可以暂时跳过；状态语义、事务边界、聚合 SQL、
TaskDecorator 的 finally 清理以及 profile-aware Security 不应跳过。
