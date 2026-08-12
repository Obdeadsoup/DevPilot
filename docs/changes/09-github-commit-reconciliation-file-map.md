# 第 9 节文件地图：Webhook 与 GitHub API 的 Commit 对账补偿

## 1. 本节目标

在不引入 MQ、不修改历史 Flyway 的前提下，让 Push Webhook 和 GitHub List Commits API 汇合到同一个
Commit Upsert；使用数据库 Sync Run、Checkpoint、唯一键和 version 实现可恢复、有限重试、可人工触发的
至少一次 Commit 对账。

## 2. 新增文件

### 数据库与配置

| 文件 | 模块 / 分层 | 职责 | 调用方 / 依赖 |
|---|---|---|---|
| `devpilot-boot/src/main/resources/db/migration/`<br>`V8__add_github_commit_reconciliation.sql` | boot / Flyway | 创建 Commit、Checkpoint、Run、约束和索引，扩展 Activity 类型 | Flyway；依赖 V1/V3/V6/V7 表 |
| `devpilot-github/../config/`<br>`GitHubReconciliationProperties.java` | github / config | 校验扫描、窗口、分页、超时和有限重试配置 | Scheduler、Run/Checkpoint/Retry 服务 |

### Commit Client 与模型

| 文件 | 模块 / 分层 | 职责 | 调用方 / 依赖 |
|---|---|---|---|
| `devpilot-github/../application/client/`<br>`GitHubCommitClient.java` | github / client boundary | List Commits 业务边界和 Link Cursor 契约 | Reconciliation；依赖 `GitHubPage` |
| `devpilot-github/../application/client/`<br>`RestClientGitHubCommitClient.java` | github / client | 映射受限 Commit 字段，首请求 since/per_page，后续沿 Cursor | Reconciliation；依赖 `GitHubApiHttpExecutor` |
| `devpilot-github/../application/client/`<br>`GitHubCommit.java` | github / client model | API Commit 的小型可信字段集合 | Commit Client、Reconciliation |
| `devpilot-github/../application/command/`<br>`UpsertGitHubCommitCommand.java` | github / command | Webhook/API 汇合后的作用域与安全元数据 | Parser、Reconciliation、Commit Service |
| `devpilot-github/../domain/`<br>`GitHubCommitSource.java` | github / domain | `WEBHOOK/API` 首次来源 | Upsert、数据库 CHECK |

### Commit Upsert

| 文件 | 模块 / 分层 | 职责 | 调用方 / 依赖 |
|---|---|---|---|
| `devpilot-github/../application/`<br>`GitHubCommitApplicationService.java` | github / application | 统一规范化、Scope 校验、DuplicateKey 并发编排 | Webhook Processing、Reconciliation |
| `devpilot-github/../application/`<br>`GitHubCommitPersistenceService.java` | github / transaction service | 独立短事务插入 Commit+Activity 或更新安全元数据 | Commit Application Service |
| `devpilot-github/../persistence/mapper/`<br>`GitHubCommitMapper.java` | github / persistence | 唯一键读取、INSERT、version 元数据 UPDATE | Commit Persistence Service |
| `devpilot-github/../persistence/entity/`<br>`GitHubCommitEntity.java` | github / persistence model | Commit 行映射，不作为 API DTO | Commit Mapper/Service |
| `devpilot-github/../application/parser/`<br>`GitHubWebhookProcessingPlan.java` | github / parser model | 同一次解析产生聚合 Activity 与 Commit commands | Delivery Processing Service |

### Checkpoint 与 Sync Run

| 文件 | 模块 / 分层 | 职责 | 调用方 / 依赖 |
|---|---|---|---|
| `devpilot-github/../application/`<br>`GitHubSyncCheckpointService.java` | github / application | 初始/重叠 since、页级进度、成功边界事务 | Reconciliation、Run State Service |
| `devpilot-github/../application/`<br>`GitHubSyncRetryPolicy.java` | github / policy | Rate Limit retryAt 与有限指数退避 | Run State Service、properties |
| `devpilot-github/../application/`<br>`GitHubSyncFailureClassifier.java` | github / classifier | API/业务异常转稳定码、可重试性、安全消息 | Reconciliation Worker |
| `devpilot-github/../application/`<br>`GitHubSyncRunStateService.java` | github / transaction service | 创建、version claim、成功、失败、超时恢复 | Run Service、Reconciliation |
| `devpilot-github/../application/`<br>`GitHubSyncRunService.java` | github / application | RBAC 人工入口、发现、恢复、扫描、提交 Executor | Controller、Scheduler |
| `devpilot-github/../application/`<br>`GitHubCommitReconciliationService.java` | github / orchestration | 无长事务的 Metadata 校验、Link 分页、Upsert、完成/失败 | Executor Worker |
| `devpilot-github/../application/`<br>`GitHubSyncScheduler.java` | github / scheduler | 薄定时触发器 | Spring Scheduling、Run Service |
| `devpilot-github/../persistence/mapper/`<br>`GitHubSyncCheckpointMapper.java` | github / persistence | Checkpoint 唯一创建和 version 进度更新 | Checkpoint Service |
| `devpilot-github/../persistence/mapper/`<br>`GitHubSyncRunMapper.java` | github / persistence | 开放 Run、候选扫描、claim、Retry/Dead/Success/恢复 | Run State/Run Service |
| `devpilot-github/../persistence/entity/`<br>`GitHubSyncCheckpointEntity.java` | github / persistence model | Checkpoint 行映射 | Checkpoint Mapper/Service |
| `devpilot-github/../persistence/entity/`<br>`GitHubSyncRunEntity.java` | github / persistence model | Run 行映射 | Run Mapper/Service/API DTO |
| `devpilot-github/../persistence/entity/`<br>`GitHubSyncTarget.java` | github / persistence projection | Binding + Project + Workspace 状态快照 | Repository Mapper、Reconciliation |
| `devpilot-github/../domain/`<br>`GitHubSyncResourceType.java` | github / domain | 当前资源类型 `COMMIT` | 数据库/未来扩展边界 |
| `devpilot-github/../domain/`<br>`GitHubSyncTriggerType.java` | github / domain | `INITIAL/SCHEDULED/MANUAL` | Run Service/State Service |
| `devpilot-github/../domain/`<br>`GitHubSyncRunStatus.java` | github / domain | Run 状态枚举 | State Service |
| `devpilot-github/../error/`<br>`GitHubSyncErrorCode.java` | github / error | Scope、Repository ID、Run/Checkpoint 冲突稳定码 | 应用/全局异常处理 |

### HTTP API

| 文件 | 模块 / 分层 | 职责 | 调用方 / 依赖 |
|---|---|---|---|
| `devpilot-github/../api/`<br>`GitHubSyncController.java` | github / controller | 人工 202 触发和安全状态查询，不接受 since | 已认证调用方、Run Service |
| `devpilot-github/../api/dto/`<br>`GitHubSyncRunReceiptResponse.java` | github / API DTO | `runId/status/existing` | POST 响应 |
| `devpilot-github/../api/dto/`<br>`GitHubSyncRunResponse.java` | github / API DTO | 安全 Run 状态，不含凭据/响应/Email | GET 响应 |

### 测试与文档

| 文件 | 模块 / 分层 | 职责 | 依赖 |
|---|---|---|---|
| `devpilot-github/src/test/../application/client/`<br>`RestClientGitHubCommitClientTest.java` | github / unit | 单页、多页、空、失败分类、恶意 Cursor、缺 SHA | Mockito/JUnit |
| `devpilot-github/src/test/../application/`<br>`GitHubCommitReconciliationServiceTest.java` | github / unit | 页中失败不推进、成功边界 | Mockito/JUnit |
| `devpilot-github/src/test/../application/`<br>`GitHubSyncCheckpointServiceTest.java` | github / unit | Lookback、overlap、推进时机 | Mockito/JUnit |
| `devpilot-github/src/test/../application/`<br>`GitHubSyncRunStateServiceTest.java` | github / unit | 单一 claim、Rate Limit、DEAD、超时恢复 | Mockito/JUnit |
| `devpilot-github/src/test/../application/`<br>`GitHubSyncRunServiceTest.java` | github / unit | 重复扫描、提交拒绝 | Mockito/JUnit |
| `devpilot-github/src/test/../application/`<br>`GitHubSyncSchedulerTest.java` | github / unit | 薄 Scheduler 只委托扫描服务 | Mockito/JUnit |
| `devpilot-boot/src/test/../github/`<br>`GitHubCommitReconciliationIntegrationTest.java` | boot / integration | API 成功推进与页中失败不越界 | MySQL/Testcontainers/MockitoBean |
| `docs/learning/09-webhook-api-reconciliation.md` | docs / learning | 本节原理、边界和未实现项 | 当前代码 |
| `docs/changes/09-github-commit-reconciliation-file-map.md` | docs / change map | 本文件 | 当前 Diff |

## 3. 修改文件

| 文件 | 模块 / 分层 | 修改职责 | 调用方 / 依赖 |
|---|---|---|---|
| `.gitignore` | root / change control | 精确放行第 9 节学习文档与文件地图 | Git 变更追踪 |
| `README.md` | root / guide | 增加 Commit 对账接口、配置和文档入口 | 开发者 |
| `docs/architecture.md` | docs / architecture | 增加双入口、Run/Checkpoint/事务链 | 开发者 |
| `docs/database-design.md` | docs / database | 增加 V8 三表、约束、索引 | 开发者/DB review |
| `docs/capability-coverage-and-roadmap.md` | docs / roadmap | 标记 Commit 对账已完成、Issue/PR 未完成 | 开发者 |
| `devpilot-boot/src/main/resources/`<br>`application.yml` | boot / config | Reconciliation 安全默认值 | properties |
| `devpilot-boot/src/test/resources/`<br>`application-test.yml` | boot / test config | 关闭自动 Scheduler | 单元/上下文测试 |
| `devpilot-boot/src/test/resources/`<br>`application-integration-test.yml` | boot / test config | 关闭自动 Scheduler | Testcontainers |
| `devpilot-github/../config/`<br>`GitHubIntegrationConfiguration.java` | github / config | 注册 Reconciliation properties | Spring Boot |
| `devpilot-github/../application/client/`<br>`GitHubApiHttpExecutor.java` | github / infrastructure | 新增受控 Query 参数 GET，仍做 Host 校验 | Commit Client |
| `devpilot-github/../application/parser/`<br>`PushWebhookPayload.java` | github / payload DTO | 增加 Commit message/time/url/author 映射 | Payload Parser |
| `devpilot-github/../application/parser/`<br>`GitHubWebhookPayloadParser.java` | github / parser | 生成 Commit command 并保留聚合 Activity | Delivery Processing |
| `devpilot-github/../application/`<br>`GitHubDeliveryProcessingService.java` | github / application | Push Commit 汇入统一 Upsert | Delivery Worker |
| `devpilot-github/../persistence/mapper/`<br>`GitHubRepositoryMapper.java` | github / persistence | Sync Target 与合格 Binding 扫描 | Run/Reconciliation |
| `devpilot-project/../domain/`<br>`ProjectActivityType.java` | project / domain | 增加 `GITHUB_COMMIT_DISCOVERED` | Commit Persistence |
| `devpilot-boot/src/test/../`<br>`IsolatedPersistenceTestConfiguration.java` | boot / test fixture | Mock 新 Mappers | 无数据库上下文测试 |
| `devpilot-boot/src/test/../github/`<br>`WebhookTestFixture.java` | boot / test fixture | 按 FK 顺序清理 V8 表 | 集成测试 |
| `devpilot-boot/src/test/../github/`<br>`GitHubWebhookIntegrationTest.java` | boot / integration | V8、双入口、并发、Checkpoint、状态/RBAC 回归 | MySQL/Testcontainers |
| `devpilot-github/src/test/../application/client/`<br>`GitHubApiHttpExecutorTest.java` | github / HTTP test | 真实 JSON、since/per_page、Link 两页、空/缺 SHA | MockRestServiceServer |
| `devpilot-github/src/test/../application/parser/`<br>`GitHubWebhookPayloadParserTest.java` | github / parser test | 验证 Push 聚合字段与 Commit Upsert command | JUnit |
| `devpilot-github/src/test/../support/`<br>`GitHubTestProperties.java` | github / test support | 新 Reconciliation 配置工厂 | 单元测试 |

本节删除文件：无。历史 migration：未修改。

## 4. 完整 Webhook Commit 调用链

```text
GitHub
→ GitHubWebhookController（原始 byte[]）
→ GitHubWebhookService（HMAC、Delivery 唯一入 Inbox）
→ TransactionalEventListener(AFTER_COMMIT) + Async
→ GitHubDeliveryWorker
→ GitHubDeliveryStateService.claim(version)
→ GitHubDeliveryProcessingService
→ GitHubWebhookPayloadParser.parseForProcessing
→ GitHubCommitApplicationService
→ GitHubCommitPersistenceService(REQUIRES_NEW)
→ GitHubCommitMapper + ProjectActivityService
→ 原 CODE_PUSHED 聚合 Activity
→ Delivery SUCCEEDED
```

## 5. 完整 API Reconcile 调用链

```text
GitHubSyncScheduler / GitHubSyncController
→ GitHubSyncRunService（发现/权限/创建/提交）
→ GitHubCommitReconciliationService
→ GitHubSyncRunStateService.claim
→ GitHubRepositoryMapper.findSyncTarget
→ GitHubSyncCheckpointService.calculateSince
→ GitHubRepositoryMetadataClient（稳定 ID 复核）
→ GitHubCommitClient
→ GitHubApiHttpExecutor
→ GitHub Commit API
→ Link Cursor 循环
→ GitHubCommitApplicationService（逐 Commit 短事务）
→ GitHubSyncCheckpointService.recordPage
→ GitHubSyncRunStateService.complete（Checkpoint + SUCCEEDED）
```

## 6. Sync Run 状态流转

```text
PENDING → RUNNING → SUCCEEDED
              ├──→ RETRY_WAIT → RUNNING
              └──→ DEAD
stale RUNNING → RETRY_WAIT / DEAD
```

开放 Run 生成列唯一索引防重复创建；状态、version、到期/截止时间条件 UPDATE 防重复 claim 和旧状态覆盖。

## 7. Checkpoint 推进链路

```text
不存在可靠边界 → now - initialLookback
存在可靠边界 → lastSuccessfulSyncAt - overlapWindow
每页全部 Commit 已提交 → version 更新 lastSeen SHA
全部 Link 页成功 → 最大 GitHub committedAt + Run SUCCEEDED 同事务
任何页失败 → 不推进到未保存页；保留原 lastSuccessfulSyncAt
```

## 8. Rate Limit 重试链路

```text
429 / 有证据的限流 403
→ GitHubApiErrorDecoder(RATE_LIMITED, retryAt)
→ HTTP 同步等待超限后返回
→ GitHubSyncFailureClassifier(retryable)
→ GitHubSyncRetryPolicy（优先 retryAt）
→ Run RETRY_WAIT
→ Scheduler 到期扫描
→ Worker version claim
```

## 9. 事务边界

- API 网络、Metadata 与 Link 分页：无数据库事务。
- Run 创建/claim/失败/超时恢复：独立短事务。
- 每个 Commit + 首次 Commit Activity：`REQUIRES_NEW`。
- 页级 lastSeen：本页全部 Commit 成功后的短事务。
- 可靠 Checkpoint + `Run → SUCCEEDED`：同一短事务。
- Webhook 聚合 Activity + Delivery SUCCEEDED：保留原事务。

## 10. 数据库唯一约束

- `dp_github_commit UNIQUE(github_repository_id, commit_sha)`：Commit 最终幂等。
- `dp_project_activity UNIQUE(source_type, source_delivery_id)`：业务 Activity 第二层幂等。
- `dp_github_sync_checkpoint UNIQUE(repository_binding_id, resource_type)`：每资源一个 Checkpoint。
- `dp_github_sync_run` 的开放状态生成列唯一键：每 Binding/资源最多一个开放 Run。
- Commit 复合外键：Binding 必须属于相同 Workspace/Project。

## 11. 关键 Diff 导读

1. 先看 V8，理解事实表、状态表、Checkpoint 和四个唯一/复合约束。
2. 看 `GitHubCommitApplicationService` 与 `GitHubCommitPersistenceService`，重点是 DuplicateKey 事务分离。
3. 看 Parser/Delivery Processing，确认聚合 Activity 保留且 Commit 汇流。
4. 看 Commit Client 与 Executor Query 重载，确认 Link/Host/字段裁剪。
5. 看 Reconciliation，确认类上没有长事务。
6. 看 Checkpoint/Run State/Mapper，确认数据先于边界、version 先于状态覆盖。
7. 最后看 Scheduler/Controller 和测试。

## 12. 推荐阅读顺序

`V8 migration → learning/09 → GitHubCommitApplicationService → GitHubCommitPersistenceService →
GitHubWebhookPayloadParser → RestClientGitHubCommitClient → GitHubCommitReconciliationService →
GitHubSyncCheckpointService → GitHubSyncRunStateService → GitHubSyncRunService → tests`。

## 13. 可跳过文件

首次阅读可跳过三个简单 API/command DTO、四个枚举和三个纯 Entity record；它们没有业务判断。Mapper 的重复
列别名也可略读，但必须精读唯一键 INSERT、version UPDATE、claim、Retry/Dead 和 Checkpoint complete SQL。
