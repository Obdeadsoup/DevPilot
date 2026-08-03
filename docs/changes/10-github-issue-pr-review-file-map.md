# 第 10 节文件地图：GitHub Issue、Pull Request 与 Review 同步

## 目标

在第 9 节 Commit Reconciliation 上增加三类当前快照的 Webhook/API 双入口、显式 Diff、Activity、
有界对账和 Project 范围只读 API；保留 Delivery、Run、Checkpoint、有限重试/DEAD 与 RBAC。

## 完整文件清单

本节没有删除文件，也没有修改 POM 或增加依赖。

### 根目录与文档（修改）

- `.gitignore`：允许跟踪第 10 节两份文档。
- `README.md`：公开事件、快照、对账和查询 API 现状。
- `docs/architecture.md`：双入口、Diff、Review 有界范围与安全边界。
- `docs/database-design.md`：V9 三表、唯一键、字段上限与 Flyway 顺序。
- `docs/capability-coverage-and-roadmap.md`：更新已完成和下一阶段。
- `devpilot-project/.../ProjectActivityType.java`：新增 Issue/PR/Review Activity 类型。

### Migration 与集成测试

- `devpilot-boot/src/main/resources/db/migration/V9__add_github_issue_pr_review_sync.sql`：三张表，扩展 resource/activity CHECK。
- `devpilot-boot/src/test/.../github/GitHubWebhookIntegrationTest.java`：Flyway、Webhook、乱序、Activity、查询集成覆盖。
- `devpilot-boot/src/test/.../github/GitHubDeliveryRecoveryIntegrationTest.java`：旧有“不支持事件”夹具改用仍未支持的 `project`，避免与本节新增 `issues` 支持冲突。
- `devpilot-boot/src/test/.../github/WebhookTestFixture.java`：按外键顺序清理三张新表。
- `devpilot-boot/src/test/.../IsolatedPersistenceTestConfiguration.java`：为无真实数据库的 Boot 上下文测试补齐三类 Snapshot Mapper mock。

### API 与 DTO（新增）

- `GitHubSnapshotController.java`：五个 authenticated Project 范围 GET。
- `GitHubSnapshotQueryService.java`：PROJECT_READ + scoped SQL 编排。
- `GitHubSnapshotPageResponse.java`、`GitHubIssueResponse.java`、`GitHubPullRequestResponse.java`、
  `GitHubPullRequestReviewResponse.java`：不暴露 Payload、凭据和 Hash；列表隐藏 Body。

### 领域、命令与实体（新增）

- `GitHubIssueStatus.java`、`GitHubPullRequestStatus.java`、`GitHubPullRequestReviewStatus.java`：当前状态。
- `GitHubSnapshotSource.java`：WEBHOOK/API_BACKFILL/API_RECONCILE。
- `UpsertGitHubIssueCommand.java`、`UpsertGitHubPullRequestCommand.java`、
  `UpsertGitHubPullRequestReviewCommand.java`：三个入口共用的强类型命令。
- `GitHubIssueEntity.java`、`GitHubPullRequestEntity.java`、`GitHubPullRequestReviewEntity.java`：持久化快照。

### Webhook（新增）

- `GitHubWebhookParserSupport.java`：关键字段、时间、Repository Scope 公共校验。
- `GitHubIssueWebhookParser.java`、`GitHubPullRequestWebhookParser.java`、
  `GitHubPullRequestReviewWebhookParser.java`：业务强类型 Parser。
- `GitHubWebhookActionMetrics.java`：未知 action 的固定 event 标签指标。

### Webhook（修改）

- `GitHubWebhookService.java`：事件白名单增加三类；Secret 解析日志移除 Reference。
- `GitHubWebhookPayloadParser.java`：按事件委派强类型 Parser、未知 action 返回空计划。
- `GitHubWebhookProcessingPlan.java`：携带 Commit/Issue/PR/Review 命令。
- `GitHubDeliveryProcessingService.java`：依次调用统一 Upsert，再条件写聚合 Activity。

### 统一 Upsert、Diff 与安全（新增）

- `GitHubExternalContentPolicy.java`：限长、稳定 JSON、GitHub HTTPS URL、SHA-256。
- `GitHubSnapshotDiffService.java`：显式选择最多一个语义 Activity。
- `GitHubIssueApplicationService.java`、`GitHubIssuePersistenceService.java`：Issue 编排/短事务。
- `GitHubPullRequestApplicationService.java`、`GitHubPullRequestPersistenceService.java`：PR 编排/短事务。
- `GitHubPullRequestReviewApplicationService.java`、`GitHubPullRequestReviewPersistenceService.java`：Review 编排/短事务。
- `GitHubSyncErrorCode.java`（修改）：Snapshot scope/invalid/state/not-found 稳定错误。

### Mapper（新增）

- `GitHubIssueMapper.java`：stable ID/Project 查询及 updatedAt+version 条件更新。
- `GitHubPullRequestMapper.java`：同上，并提供有界 Review 候选和 PR 级水位。
- `GitHubPullRequestReviewMapper.java`：独立 Review ID Upsert 与 PR-scoped 查询。

### API Client（新增）

- `GitHubIssue.java`、`GitHubPullRequest.java`、`GitHubPullRequestReview.java`：最小外部 DTO。
- `GitHubIssueClient.java`、`GitHubPullRequestClient.java`、`GitHubPullRequestReviewClient.java`：Client 边界。
- `GitHubSnapshotClientSupport.java`：时间、JSON、Malformed 分类公共代码。
- `RestClientGitHubIssueClient.java`：Issues API + PR 过滤。
- `RestClientGitHubPullRequestClient.java`：Pulls API + 真实 PR ID/head/base。
- `RestClientGitHubPullRequestReviewClient.java`：指定 PR 的 Reviews API。

### Reconciliation（新增）

- `GitHubReconciliationWorker.java`、`GitHubReconciliationDispatcher.java`：按持久化 resource_type 分发。
- `AbstractGitHubSnapshotReconciliationService.java`：claim、目标/ID 校验、Checkpoint、失败分类模板。
- `GitHubIssueReconciliationService.java`：since/overlap Issue 分页。
- `GitHubPullRequestReconciliationService.java`：updated desc 到边界停止。
- `GitHubPullRequestReviewReconciliationService.java`：近期且水位落后的有限 PR 批次。
- `GitHubReviewSyncProgressService.java`：单 PR 全分页后推进水位的短事务。

### Reconciliation（修改）

- `GitHubSyncResourceType.java`：新增 ISSUE/PULL_REQUEST/PULL_REQUEST_REVIEW。
- `GitHubCommitReconciliationService.java`：实现通用 Worker 接口。
- `GitHubSyncCheckpointMapper.java`、`GitHubSyncCheckpointService.java`：按 resource_type 通用化，保留 Commit 兼容入口。
- `GitHubSyncRunMapper.java`、`GitHubSyncRunStateService.java`：按 resource_type 创建/查找开放 Run。
- `GitHubSyncRunService.java`：为四类资源发现 Run，并通过 Dispatcher 提交。

### 单元测试（新增）

- `GitHubSnapshotWebhookParserTest.java`：三 Parser、ID、draft/action/status、未知 action。
- `GitHubSnapshotDiffServiceTest.java`：状态、draft/head、Review Activity。
- `GitHubExternalContentPolicyTest.java`：限长、JSON、URL 与 Hash。
- `GitHubSnapshotClientsTest.java`：Issue/PR 过滤、PR/Review ID、分页、错误分类。
- `GitHubSnapshotReconciliationServiceTest.java`：Checkpoint 失败不提前推进、Review 有界候选。

## 逐文件模块、分层与依赖矩阵

为避免路径重复，以下缩写都可机械展开为 Repository 相对路径：

- `G` = `devpilot-github/src/main/java/com/obdeadsoup/devpilot/github`
- `GT` = `devpilot-github/src/test/java/com/obdeadsoup/devpilot/github`
- `B` = `devpilot-boot/src`
- `P` = `devpilot-project/src/main/java/com/obdeadsoup/devpilot/project`

“调用/依赖”列按 `主要调用方 → 主要依赖对象` 表达；文档和配置没有运行时调用方时写明用途。

| 文件 | 模块 / 分层 | 职责 | 调用 / 依赖 |
|---|---|---|---|
| `.gitignore` | root / 配置 | 放行本节两份文档 | Git 工作区 → `docs/changes/10-*`、`docs/learning/10-*` |
| `README.md` | root / 文档 | 对外说明三类快照能力 | 学习者 → 架构与 API 入口 |
| `docs/architecture.md` | docs / 架构 | 记录双入口与有界 Review 对账 | 设计审查 → Application、Reconciliation |
| `docs/database-design.md` | docs / 数据 | 记录 V9 表、约束、字段上限 | 数据库审查 → V9 |
| `docs/capability-coverage-and-roadmap.md` | docs / 路线图 | 更新当前能力与后续边界 | 学习者 → 已实现代码 |
| `docs/changes/10-github-issue-pr-review-file-map.md` | docs / 变更地图 | 本节逐文件和调用链索引 | 学习者 → 本表全部文件 |
| `docs/learning/10-github-issue-pr-review-sync.md` | docs / 学习 | 原理、手测、安全和边界 | 学习者 → 当前实现与 SQL |
| `B/main/resources/db/migration/V9__add_github_issue_pr_review_sync.sql` | boot / Migration | 建三张快照表并扩展 CHECK | Flyway → V1-V8、MySQL 8 |
| `B/test/java/com/obdeadsoup/devpilot/IsolatedPersistenceTestConfiguration.java` | boot / 测试配置 | 提供隔离上下文 Mapper mock | Boot 上下文测试 → Mockito、全部 Mapper |
| `B/test/java/com/obdeadsoup/devpilot/github/GitHubDeliveryRecoveryIntegrationTest.java` | boot / 集成测试 | 保持不支持事件夹具语义 | Surefire → Delivery Recovery、MySQL |
| `B/test/java/com/obdeadsoup/devpilot/github/GitHubWebhookIntegrationTest.java` | boot / 集成测试 | 覆盖迁移、Webhook、快照、Activity、查询 | Surefire → MockMvc、Testcontainers、Application Service |
| `B/test/java/com/obdeadsoup/devpilot/github/WebhookTestFixture.java` | boot / 测试夹具 | 建 Scope 数据并按外键清表 | Webhook 集成测试 → JdbcTemplate、V9 表 |
| `P/domain/ProjectActivityType.java` | project / 领域 | 声明 Issue/PR/Review Activity 类型 | Snapshot Diff → V9 Activity CHECK |
| `G/api/GitHubSnapshotController.java` | github / API | 暴露五个 Project 范围只读 GET | Spring MVC → Query Service |
| `G/api/dto/GitHubIssueResponse.java` | github / API DTO | Issue 列表/详情安全投影 | Query Service → Issue Entity |
| `G/api/dto/GitHubPullRequestResponse.java` | github / API DTO | PR 列表/详情安全投影 | Query Service → PR Entity |
| `G/api/dto/GitHubPullRequestReviewResponse.java` | github / API DTO | Review 安全投影 | Query Service → Review Entity |
| `G/api/dto/GitHubSnapshotPageResponse.java` | github / API DTO | 统一分页响应 | Query Service → DTO items |
| `G/domain/GitHubIssueStatus.java` | github / 领域 | Issue OPEN/CLOSED | Parser、Client → Upsert Command |
| `G/domain/GitHubPullRequestStatus.java` | github / 领域 | 按 merged 优先计算 PR 状态 | Parser、Client → Upsert Command |
| `G/domain/GitHubPullRequestReviewStatus.java` | github / 领域 | 映射 Review 当前状态 | Parser、Client → Upsert Command |
| `G/domain/GitHubSnapshotSource.java` | github / 领域 | 区分 Webhook/Backfill/Reconcile | Parser、Reconciliation → Activity 策略 |
| `G/domain/GitHubSyncResourceType.java` | github / 领域（修改） | 扩展四类 Sync Resource | Scheduler、Run、Checkpoint → Dispatcher |
| `G/error/GitHubSyncErrorCode.java` | github / 错误（修改） | 增加 Snapshot 稳定错误码 | Application、Client → Global Handler |
| `G/application/command/UpsertGitHubIssueCommand.java` | github / Command | Issue 强类型统一入口 | Parser、Client → Issue Application Service |
| `G/application/command/UpsertGitHubPullRequestCommand.java` | github / Command | PR 强类型统一入口 | Parser、Client → PR Application Service |
| `G/application/command/UpsertGitHubPullRequestReviewCommand.java` | github / Command | Review 强类型统一入口 | Parser、Client → Review Application Service |
| `G/persistence/entity/GitHubIssueEntity.java` | github / Entity | Issue 当前快照读模型 | Issue Mapper → Application/DTO |
| `G/persistence/entity/GitHubPullRequestEntity.java` | github / Entity | PR 当前快照读模型及 Review 水位 | PR Mapper → Application/Reconciliation/DTO |
| `G/persistence/entity/GitHubPullRequestReviewEntity.java` | github / Entity | Review 当前快照读模型 | Review Mapper → Application/DTO |
| `G/application/parser/GitHubWebhookParserSupport.java` | github / Parser | 校验公共关键字段和 Scope | 三 Parser → ObjectMapper、Binding、Content Policy |
| `G/application/parser/GitHubIssueWebhookParser.java` | github / Parser | action 与 Issue snapshot 分离 | Payload Parser → Issue Command |
| `G/application/parser/GitHubPullRequestWebhookParser.java` | github / Parser | 解析真实 PR ID、draft、head/base | Payload Parser、Review Parser → PR Command |
| `G/application/parser/GitHubPullRequestReviewWebhookParser.java` | github / Parser | 解析独立 Review ID 及嵌入 PR | Payload Parser → PR/Review Command |
| `G/application/parser/GitHubWebhookPayloadParser.java` | github / Parser（修改） | 按事件路由，未知 action 安全忽略 | Delivery Processing → 各强类型 Parser、Metrics |
| `G/application/parser/GitHubWebhookProcessingPlan.java` | github / Parser DTO（修改） | 承载四类强类型命令 | Payload Parser → Delivery Processing |
| `G/application/GitHubWebhookActionMetrics.java` | github / 可观测性 | 低基数记录未知 action | Payload Parser → MeterRegistry |
| `G/application/GitHubWebhookService.java` | github / 接收（修改） | 放行三事件并保护 Secret 日志 | Controller → Signature、Delivery Mapper |
| `G/application/GitHubDeliveryProcessingService.java` | github / 处理（修改） | 执行四类统一 Upsert | Delivery Worker → Parser、四 Application Service |
| `G/application/GitHubExternalContentPolicy.java` | github / 安全 | 限长、稳定 JSON、URL、content hash | Parser/Application/Client → ObjectMapper、SHA-256 |
| `G/application/GitHubSnapshotDiffService.java` | github / 领域服务 | 显式选择最多一个语义 Activity | 三 Persistence Service → Activity Type |
| `G/application/GitHubIssueApplicationService.java` | github / 应用服务 | Scope、乱序、Hash、并发 Issue Upsert | Webhook/Reconcile → Mapper、Policy、Persistence |
| `G/application/GitHubIssuePersistenceService.java` | github / 事务服务 | Issue 条件写与 Activity 同事务 | Issue Application → Mapper、Diff、Activity Service |
| `G/application/GitHubPullRequestApplicationService.java` | github / 应用服务 | Scope、乱序、Hash、并发 PR Upsert | Webhook/Reconcile → Mapper、Policy、Persistence |
| `G/application/GitHubPullRequestPersistenceService.java` | github / 事务服务 | PR 条件写与 Activity 同事务 | PR Application → Mapper、Diff、Activity Service |
| `G/application/GitHubPullRequestReviewApplicationService.java` | github / 应用服务 | 独立 Review ID、Scope、乱序 Upsert | Webhook/Reconcile → PR/Review Mapper、Persistence |
| `G/application/GitHubPullRequestReviewPersistenceService.java` | github / 事务服务 | Review 条件写与 Activity 同事务 | Review Application → Mapper、Diff、Activity Service |
| `G/application/GitHubSnapshotQueryService.java` | github / 查询服务 | RBAC 后执行 Project scoped SQL | Controller → CurrentUser、Authorization、三 Mapper |
| `G/application/client/GitHubIssue.java` | github / Client DTO | Issues API 最小数据 | Issue Client → Reconciliation |
| `G/application/client/GitHubPullRequest.java` | github / Client DTO | Pulls API 最小数据 | PR Client → Reconciliation |
| `G/application/client/GitHubPullRequestReview.java` | github / Client DTO | Reviews API 最小数据 | Review Client → Reconciliation |
| `G/application/client/GitHubIssueClient.java` | github / Client Port | 定义 Issue 增量分页边界 | Issue Reconciliation → 实现 |
| `G/application/client/GitHubPullRequestClient.java` | github / Client Port | 定义 PR updated-desc 分页边界 | PR Reconciliation → 实现 |
| `G/application/client/GitHubPullRequestReviewClient.java` | github / Client Port | 定义单 PR Review 分页边界 | Review Reconciliation → 实现 |
| `G/application/client/GitHubSnapshotClientSupport.java` | github / Client 支持 | 时间、JSON、Malformed 分类 | 三 RestClient 实现 → ObjectMapper |
| `G/application/client/RestClientGitHubIssueClient.java` | github / Client Adapter | 调 Issues API 并过滤 PR | Issue Client Port → HTTP Executor、Support |
| `G/application/client/RestClientGitHubPullRequestClient.java` | github / Client Adapter | 调 Pulls API 并读取真实 PR ID | PR Client Port → HTTP Executor、Support |
| `G/application/client/RestClientGitHubPullRequestReviewClient.java` | github / Client Adapter | 按 PR 分页调 Reviews API | Review Client Port → HTTP Executor、Support |
| `G/application/GitHubReconciliationWorker.java` | github / Reconcile Port | 统一四资源 Worker 契约 | Dispatcher → 具体 Reconciliation Service |
| `G/application/GitHubReconciliationDispatcher.java` | github / Reconcile 分发 | 按 resource_type 找 Worker | Sync Run Service → Worker Map |
| `G/application/AbstractGitHubSnapshotReconciliationService.java` | github / Reconcile 模板 | claim、Scope/ID、Checkpoint、失败分类 | 三 Snapshot Reconcile → Run/Checkpoint/Metadata Client |
| `G/application/GitHubIssueReconciliationService.java` | github / Reconcile | Issue overlap 分页和统一 Upsert | Dispatcher → Issue Client、Issue Application |
| `G/application/GitHubPullRequestReconciliationService.java` | github / Reconcile | PR updated-desc 到边界停止 | Dispatcher → PR Client、PR Application |
| `G/application/GitHubPullRequestReviewReconciliationService.java` | github / Reconcile | 有限 PR 候选逐个 Review 分页 | Dispatcher → PR Mapper、Review Client/Application |
| `G/application/GitHubReviewSyncProgressService.java` | github / 事务服务 | 单 PR 全分页后推进水位 | Review Reconcile → PR Mapper |
| `G/application/GitHubCommitReconciliationService.java` | github / Reconcile（修改） | 适配通用 Worker 接口 | Dispatcher → 原 Commit Client/Application |
| `G/application/GitHubSyncCheckpointService.java` | github / Sync（修改） | 四资源 Checkpoint 读写 | Reconciliation → Checkpoint Mapper |
| `G/application/GitHubSyncRunService.java` | github / Sync（修改） | 发现四资源 Run 并提交分发 | Scheduler/Manual API → Run State、Dispatcher |
| `G/application/GitHubSyncRunStateService.java` | github / Sync（修改） | 四资源 Run claim/成功/失败短事务 | Reconciliation → Run/Checkpoint Mapper |
| `G/persistence/mapper/GitHubIssueMapper.java` | github / Mapper | Issue stable ID、scoped query、双条件更新 | Issue Services/Query → `dp_github_issue` |
| `G/persistence/mapper/GitHubPullRequestMapper.java` | github / Mapper | PR stable ID、Review 候选/水位、双条件更新 | PR/Review Services/Query → `dp_github_pull_request` |
| `G/persistence/mapper/GitHubPullRequestReviewMapper.java` | github / Mapper | Review stable ID、PR scoped query、双条件更新 | Review Services/Query → `dp_github_pull_request_review` |
| `G/persistence/mapper/GitHubSyncCheckpointMapper.java` | github / Mapper（修改） | 按 resource_type 读写 Checkpoint | Checkpoint Service → `dp_github_sync_checkpoint` |
| `G/persistence/mapper/GitHubSyncRunMapper.java` | github / Mapper（修改） | 按 resource_type 创建/查找 Run | Run Services → `dp_github_sync_run` |
| `GT/application/GitHubExternalContentPolicyTest.java` | github / 单元测试 | 限长、JSON、URL、Hash | JUnit → Content Policy |
| `GT/application/GitHubSnapshotDiffServiceTest.java` | github / 单元测试 | Issue/PR/Review 语义 Diff | JUnit → Diff Service、状态枚举 |
| `GT/application/GitHubSnapshotReconciliationServiceTest.java` | github / 单元测试 | Checkpoint 时机和 Review 有界候选 | JUnit/Mockito → Reconciliation Services |
| `GT/application/client/GitHubSnapshotClientsTest.java` | github / 单元测试 | Issue 过滤、PR/Review ID 与分页/错误 | JUnit/Mockito → 三 RestClient Adapter |
| `GT/application/parser/GitHubSnapshotWebhookParserTest.java` | github / 单元测试 | action/status、ID、draft、未知 action | JUnit → 三 Parser |

没有删除文件；根 POM 与子模块 POM 均未修改，依赖集合没有变化。

## 调用链

Issue Webhook：

```text
GitHubWebhookController → GitHubWebhookService → Delivery Inbox/AFTER_COMMIT Worker
→ GitHubIssueWebhookParser → GitHubIssueApplicationService
→ GitHubIssuePersistenceService → GitHubIssueMapper + SnapshotDiff + ProjectActivityService
```

PR Webhook：同一路径进入 `GitHubPullRequestWebhookParser → GitHubPullRequestApplicationService`。

Review Webhook：`GitHubPullRequestReviewWebhookParser` 先输出无 Activity 来源键的嵌入 PR 快照，
`GitHubPullRequestApplicationService` 保证本地 PR 外键存在，再由
`GitHubPullRequestReviewApplicationService` 保存 Review 并独占 Delivery Activity 来源键。

Issue API 对账：

```text
Scheduler → SyncRunService → Dispatcher → IssueReconciliationService
→ Checkpoint overlap → Metadata ID 复核 → IssueClient（过滤 PR）
→ IssueApplicationService → 数据全部成功 → Checkpoint + Run SUCCEEDED
```

PR API 对账把 Client 换成 Pull Requests API，并按 updated desc 到边界停止。Review 对账从
`GitHubPullRequestMapper.findReviewCandidates` 取得有限 PR，逐 PR 分页后推进 `reviews_synced_at`。

## Snapshot Diff、时间与并发

Application Service 读取旧快照并拒绝更早 `github_updated_at`；相同时间+相同 Hash 直接幂等返回。
Persistence Service 在独立短事务用 `id + version + github_updated_at <= incoming` 更新，并在同事务
调用 Diff/Activity。外部 updatedAt 表达 GitHub 顺序；本地 version 表达数据库写竞争。

## 唯一约束与事务

三类 stable ID 唯一键是首次并发插入的最终防线，Issue/PR number 另有 Repository 范围唯一键。
Activity `(source_type, source_delivery_id)` 保证每个 Delivery 最多一条。网络调用不持有事务；快照写、
PR Review 水位、Checkpoint+SUCCEEDED、失败状态分别采用短事务。

## 推荐阅读顺序

1. V9 migration 与三个 status/source 枚举。
2. 三个 Webhook Parser 和 ProcessingPlan。
3. ExternalContentPolicy、三个 ApplicationService、SnapshotDiff。
4. 三个 Mapper/PersistenceService。
5. 三个 Client。
6. Run/Checkpoint 通用化与三 Reconciliation Service。
7. Query Service/Controller。
8. 单元与 Testcontainers 集成测试。

## 关键 Diff 导读

- 先看 V9 的 stable ID、Scope 外键、Body/JSON 上限与 Review 水位。
- 再看 `updateSnapshot` 的 `version + github_updated_at` 双条件。
- 对比三个 Application Service 的 stale/hash/DuplicateKey 路径。
- 最后看 Review Parser 为什么先 Upsert PR、Review 对账为什么只选有限候选。

可暂时跳过四个响应 DTO、三个 client 数据 record、三个 persistence entity 与重复字段 Mapper；它们是
显式边界所需的机械映射，不承载核心并发规则。
