# 第 9 节：Webhook 与 GitHub API 的 Commit 对账补偿

## 1. 为什么同时需要 Webhook 和 API

Webhook 是 GitHub 主动推送，延迟低、请求成本小，适合实时更新；但网络抖动、配置错误、应用停机或
GitHub 投递失败都可能留下缺口。List Commits API 是 DevPilot 主动读取，实时性较弱且受 Rate Limit
约束，却能按时间窗口重新枚举数据。因此当前方案是：Webhook 提供实时性，API Reconciliation 提供完整性。

这不是把两份结果相加，而是两个入口最终汇合到同一个 Commit Upsert。

## 2. Commit 双入口

Webhook 路径：

```text
GitHubWebhookController
→ GitHubWebhookService（验签、Delivery Inbox）
→ AFTER_COMMIT + Async
→ GitHubDeliveryWorker（version claim）
→ GitHubDeliveryProcessingService
→ GitHubWebhookPayloadParser
→ GitHubCommitApplicationService（source=WEBHOOK）
```

API 路径：

```text
GitHubSyncScheduler / GitHubSyncController
→ GitHubSyncRunService
→ GitHubCommitReconciliationService（claim Run）
→ GitHubRepositoryMetadataClient（复核稳定 Repository ID）
→ GitHubCommitClient（List Commits + Link Cursor）
→ GitHubCommitApplicationService（source=API）
```

Push 原有的一条 `CODE_PUSHED` 聚合 Activity 被保留，它表达“一次 Push”；每个首次发现的 Commit 还会有
一条 `GITHUB_COMMIT_DISCOVERED` Activity，它表达“一条 Commit 首次进入本地”。两者语义不同。

## 3. `repositoryId + SHA` 幂等

`dp_github_commit` 以 `(github_repository_id, commit_sha)` 为业务唯一键。SHA 只在同一个 Repository
范围内解释，不能单独作为全局业务标识。首次插入 Commit 和 Commit Activity 位于同一个独立短事务；
后续来源可以补充非空安全元数据，但不能覆盖 `first_seen_source`，也不能创建第二条 Activity。

应用层先查只用于减少异常和表达意图，最终仲裁仍是数据库唯一索引。并发双方都认为“不存在”时，只有一个
INSERT 能成功。竞争方的 `DuplicateKeyException` 事务先完整回滚，再在新事务中读取胜者；这避免 MySQL
`REPEATABLE READ` 旧快照看不到并发提交行，也避免在失败事务中继续做锁定读。

Commit Activity 还有 `(source_type, source_delivery_id)` 唯一键，稳定来源键为
`commit:{githubRepositoryId}:{sha}`。因此 Commit 行和业务 Activity 是两层幂等。

## 4. Sync Run 与 Checkpoint

`dp_github_sync_run` 表示一次可执行、可恢复、可观察的同步任务；`dp_github_sync_checkpoint` 表示已被本地
持久化事实支持的读取边界。Run 回答“本次执行到什么状态”，Checkpoint 回答“下次从哪里重新覆盖读取”。

Run 状态机：

```text
PENDING ──claim──> RUNNING ──success──> SUCCEEDED
                       ├──retryable──> RETRY_WAIT ──due claim──> RUNNING
                       └──permanent / attempts exhausted──> DEAD

stale RUNNING ──timeout recovery──> RETRY_WAIT / DEAD
```

同一 Binding/COMMIT 只允许一个 `PENDING/RUNNING/RETRY_WAIT` 开放 Run。唯一索引保护“只创建一个”，
`version + status + time` 条件 UPDATE 保护“只有一个 Worker claim 或迁移状态”。

## 5. overlapWindow

初次同步没有可靠边界，使用受配置限制的 `initialLookback`，默认 7 天。后续使用：

```text
since = lastSuccessfulSyncAt - overlapWindow
```

例如可靠边界是 `10:30`，默认重叠 5 分钟，则下一轮从 `10:25` 读取。10:25–10:30 的 Commit 会再次出现，
但统一 Upsert 和 Activity 唯一键使重复只增加读取成本，不增加重复业务结果。这个成本换取了时钟边界、提交
可见性延迟和 API 时间筛选边界附近更低的漏数概率。

## 6. 为什么允许重复读取

外部系统无法与本地数据库组成一个 ACID 事务。试图精确记住“最后读到第几条”会把分页变化、失败位置和
并发提交变成脆弱状态。至少一次读取加本地幂等更简单：允许旧数据重放，把正确性落在唯一键和 Upsert 上。

## 7. Checkpoint 何时推进

每页所有 Commit 都通过独立短事务持久化后，才可以写 `last_seen_commit_sha` 页级安全进度。如果当前页中途
失败，不调用页级推进，因此不会越过尚未保存的数据。

整轮所有 Link 页成功后，才推进 `last_successful_sync_at`，并与 `Run → SUCCEEDED` 在同一个短事务提交。
可靠边界取本轮 GitHub Commit 的最大 `committed_at`，不会用本机 `now()` 简单覆盖。空的初次结果不会制造
一个没有数据依据的边界；后续会继续读取受限 Lookback，代价是重复请求而不是漏数。

## 8. 网络调用与事务边界

`GitHubCommitReconciliationService` 没有覆盖整个方法的 `@Transactional`。Repository Metadata、每个
List Commits 页面以及 Link 翻页都发生在数据库事务外。原因是 GitHub 延迟和 Retry 时间不可控；把网络
包进事务会长期占用连接、延长行锁、放大死锁和连接池耗尽风险。

当前短事务是：

- 创建/claim/失败迁移各自一个 Run 状态事务；
- 单个 Commit + 首次 Commit Activity 一个 `REQUIRES_NEW` 短事务；
- 一页全部 Commit 成功后的页级 Checkpoint 事务；
- 最终 Checkpoint 可靠边界 + `Run → SUCCEEDED` 一个事务；
- 失败记录使用独立事务，避免被此前业务异常回滚。

Webhook 的 Delivery 聚合 Activity 与 `SUCCEEDED` 仍在原成功事务中；Commit 明细独立提交，因此中途失败
重跑时会复用唯一键继续，而不会重复 Commit Activity。

## 9. 分页处理

首个请求只发送系统计算的 `since` 和配置的 `per_page`。后续页只使用 `Link` Header 中经固定 Host 策略
校验的 `GitHubPageCursor`，不会手工 `page++`。这样可以保留 GitHub 的不透明分页语义，并阻止恶意 next
Host 把 Bearer Token 带到非 GitHub 地址。

Client 只解析 SHA、message、Git author、GitHub author id/login、author/committer 时间和 `html_url`；不保存
Patch、文件列表或大响应。`author_email` 允许内部持久化，但当前普通 API 没有返回 Commit，也不会暴露它。

## 10. Rate Limit 到 RETRY_WAIT

统一 HTTP Executor 先完成单次读取内的有限 Retry。若等待时间超过同步 HTTP 上限，`GitHubApiException`
携带安全分类和未来 `retryAt` 返回。Sync Failure Classifier 把 `RATE_LIMITED` 标为可重试，Run State Service
优先采用该未来时间进入 `RETRY_WAIT`。Scheduler 到期后重新发现，Worker 再次通过 version claim。

网络错误和临时 5xx 也只有在 HTTP Retry 耗尽后才进入 Run 级有限退避。401、普通 403、Repository ID
mismatch、Validation 和 Malformed Response 是确定性错误，直接 `DEAD`。到达 `maxRunAttempts` 后，可重试
错误也转 `DEAD`，不会无限循环。

## 11. Scheduler 扫描与 claim

Scheduler 只是周期调用 `GitHubSyncRunService.discoverAndSubmit`：恢复超时 RUNNING、为合格 ACTIVE Binding
创建/查找开放 Run、扫描 PENDING/到期 RETRY_WAIT 并提交线程池。多个实例可能读到同一个候选，这不是错误。

线程池任务真正执行时才 claim。只有条件 UPDATE 成功的 Worker 进入网络流程；其余安全返回。线程池拒绝发生
在 claim 之前，因此 Run 仍保持数据库可扫描状态，不会出现“数据库显示 RUNNING、实际上从未执行”。

## 12. 人工补偿

`POST .../github-repositories/{bindingId}/sync/commits` 需要认证和 `REPOSITORY_UPDATE`，只接受 URL Scope，
不接受用户自定义 since。它创建 MANUAL Run 并立即返回 `202 + runId`，不等待分页结束。同一 Binding 已有
开放 Run 时返回该 Run，并以 `existing=true` 告知调用方；这比再创建一个竞争任务更稳定。

只读 `GET .../sync-runs/{runId}` 需要 `REPOSITORY_READ`，返回状态、稳定错误码和时间，不返回 Credential、
作者 Email 或 GitHub 响应。

## 13. 至少一次同步语义

本链路不是 Exactly Once。Scheduler 可重复扫描、线程池可重复提交、失败页可被整轮重读、Webhook 和 API
也可先后看到同一 SHA。系统提供的是至少一次读取/执行尝试，加数据库唯一键和 version 条件形成“业务结果
最多一份”。这种语义能够被故障测试和数据库约束验证，而不是依赖内存事件或单实例假设。

## 14. 当前边界

本节只完成 Commit 对账。尚未实现 Issue/PR/Review 对账、GitHub App installation token、管理员可调补偿窗口、
人工复活 DEAD、Outbox/MQ、跨资源统一同步编排和 Commit 查询 API。当前 Executor 仍是单 JVM Credential
并发限制；数据库 claim 负责同步任务的跨实例互斥。
