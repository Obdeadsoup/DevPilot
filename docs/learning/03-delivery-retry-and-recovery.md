# 第 3 节：GitHub Delivery 重试与崩溃恢复

## 1. Spring Event 的故障窗口

Webhook 接收事务提交后，`GitHubDeliveryEventListener` 通过进程内 Spring Event 和异步线程池立即
触发 Worker。它降低了正常请求的处理延迟，但事件本身没有持久化。数据库已经提交、异步任务尚未开始时
如果进程崩溃，内存事件会消失，Delivery 会停留在 `RECEIVED`。

线程池队列拒绝、应用强制退出和节点重启都会暴露类似窗口。因此 Spring Event 只是低延迟触发信号，
不能作为可靠任务存储。

## 2. MySQL Delivery 才是可靠任务源

`dp_github_delivery` 在返回 `202 Accepted` 前保存了 Delivery ID、事件类型、Payload、Hash、状态和接收
时间。进程重启后这些事实仍然存在。本轮恢复扫描以 Delivery 表为事实来源，重新发现：

- 没有收到或执行内存信号的 `RECEIVED`；
- 已到重试时间的 `RETRY_WAIT`；
- Worker 崩溃后超过租约时间的 `PROCESSING`。

扫描器只读取有限批次并提交 Delivery ID。真正处理前仍由 Worker 执行数据库条件 claim。

## 3. 每个状态的准确语义

- `RECEIVED`：请求已验签并持久化，尚未被 Worker 抢占。
- `PROCESSING`：某个 Worker 已通过条件 UPDATE 获得当前处理资格，`processing_started_at` 记录租约起点。
- `SUCCEEDED`：Activity 与成功状态已经在同一处理事务中提交，是自动流程成功终态。
- `RETRY_WAIT`：最近一次处理失败但仍允许自动重试，`next_retry_at` 之前不能被 claim。
- `DEAD`：错误不可重试，或自动重试额度已经耗尽，是自动流程失败终态。
- `FAILED`：保留在 Java enum 和 V1 CHECK 中用于兼容已有结构，不再是普通自动失败路径。

实际自动状态机为：

```text
RECEIVED → PROCESSING → SUCCEEDED
             ↑        ├→ RETRY_WAIT
             └────────┘
                      └→ DEAD

超时 PROCESSING → RETRY_WAIT 或 DEAD
```

## 4. 为什么失败直接进入 `RETRY_WAIT` 或 `DEAD`

普通自动流程不需要先稳定停留在 `FAILED` 再迁移。Worker 已经能够在失败时完成分类并判断剩余重试额度，
因此一个带状态与版本条件的 UPDATE 可以直接写入最终决策：

- 可重试且仍有额度：`PROCESSING → RETRY_WAIT`；
- 不可重试或额度耗尽：`PROCESSING → DEAD`。

错误事实不会因此丢失：`last_error_code`、`last_error_message` 和递增后的 `retry_count` 会随状态一起保存。
减少一个没有业务等待意义的中间状态，也减少了需要补偿的事务边界。

## 5. 可重试与不可重试错误

`GitHubDeliveryFailureClassifier` 当前明确把以下错误归为不可重试：

- `MALFORMED_PAYLOAD`：相同坏 Payload 重试不会自行变好；
- `UNSUPPORTED_EVENT`：当前代码没有对应解析能力；
- `DELIVERY_STATE_CONFLICT`：表示状态或版本事实已经冲突，旧 Worker 不应覆盖新状态。

其他暂时无法进一步判断的 `RuntimeException` 归为可重试，数据库只写稳定错误码
`PROCESSING_ERROR` 和安全消息 `Delivery processing failed`。不会写入原始 Payload、Secret、SQL 或完整异常消息。

崩溃超时恢复使用独立稳定错误码 `WORKER_TIMEOUT` 和安全消息
`Delivery processing timed out`。

## 6. `maxRetries` 与总尝试次数

`retry_count` 的语义是已发生的处理失败次数：

- 新 Delivery 初始为 0；
- 每次处理失败或 PROCESSING 超时恢复时加 1；
- 成功不会增加；
- `deliveryMaxRetries` 表示首次处理之外允许的自动重试次数。

例如 `deliveryMaxRetries=3` 时，最多执行首次处理 1 次和自动重试 3 次，共 4 次。前三次失败分别进入
`RETRY_WAIT`，`retry_count` 为 1、2、3；第四次执行仍失败时进入 `DEAD`，`retry_count` 为 4。
若配置为 0，首次失败直接进入 `DEAD`。

## 7. 确定性的指数退避

第 `retry_count` 次失败后的延迟公式为：

```text
min(deliveryRetryMaxDelay,
    deliveryRetryInitialDelay × 2^(retry_count - 1))
```

默认初始延迟 10 秒、最大延迟 5 分钟时，延迟依次为 10 秒、20 秒、40 秒、80 秒、160 秒、300 秒，
之后保持 300 秒。实现会在乘法前判断是否将超过上限，并捕获 `Duration` 溢出；极大计数也只返回最大延迟。
当前没有加入随机抖动，以便行为和测试保持确定。

## 8. 三类恢复扫描

每轮 `GitHubDeliveryRecoveryService` 按以下顺序执行：

1. 查询 `processing_started_at <= now - deliveryProcessingTimeout` 的过期 `PROCESSING`，按策略恢复；
2. 查询最早接收的 `RECEIVED`；
3. 查询 `next_retry_at <= now` 的 `RETRY_WAIT`；
4. 对后两类候选向 `githubDeliveryTaskExecutor` 提交 ID。

每个查询都有 `deliveryRecoveryBatchSize` 限制。线程池拒绝任务时只记录 Delivery ID 和异常类型，不更新
Delivery；它仍保持 `RECEIVED` 或 `RETRY_WAIT`，等待下一轮扫描。

## 9. `processing_started_at` 的租约语义

claim 成功时写入 `processing_started_at`。它不是永久所有权，而是 Worker 当前处理资格的时间起点。
扫描器把早于超时 cutoff 的 `PROCESSING` 视为租约过期：原 Worker 可能已经崩溃或失联。

恢复仍检查 `id + PROCESSING + version + processing_started_at <= cutoff`。如果原 Worker 已经成功、失败，
或另一个操作改变了版本，恢复 UPDATE 返回 0，不会覆盖更新后的状态。

## 10. 多实例扫描不依赖分布式锁

多个应用实例可能同时扫描到同一个 ID，这是允许的。扫描 SELECT 只是发现候选，不授予所有权。即使多个
实例都把同一 ID 放入本地线程池，最终也只有一个 Worker 能把符合条件的行更新为 `PROCESSING`。

数据库是共同协调点，因此不需要 Redis 锁或 ShedLock。避免重复扫描不是正确性的前提，条件 UPDATE 才是。

## 11. 扫描发现与数据库 claim 的区别

扫描查询回答“哪些任务看起来可以尝试”，结果在返回后立刻可能过期。claim 回答“当前事务是否真正获得
处理资格”，规则为：

```sql
WHERE id = ?
  AND version = ?
  AND (
    processing_status = 'RECEIVED'
    OR (processing_status = 'RETRY_WAIT' AND next_retry_at <= now)
  )
```

只有更新行数为 1 才能继续处理。`SUCCEEDED`、`DEAD`、未到期 `RETRY_WAIT` 和版本已变化的记录都无法
被 claim。

## 12. 状态与 `version` 条件更新

claim、成功、重试等待、DEAD 和超时恢复全部同时检查预期状态与 `version`，成功后 `version + 1`。
旧 Worker 即使晚到，也无法用旧版本执行 `markSucceeded` 或 `markRetryWait`。这防止超时恢复后的新状态
被原 Worker 覆盖。

本轮 V2 只新增索引：

```sql
(processing_status, processing_started_at)
```

它服务于过期 PROCESSING 的范围和排序查询。现有
`(processing_status, next_retry_at)` 继续服务到期重试扫描；V1 没有被修改。

## 13. 至少一次处理与业务幂等

恢复扫描与并发提交意味着 Delivery 可能被尝试多次，系统提供的是至少一次尝试，而不是严格恰好一次。
正确性依赖两层幂等：

1. `github_delivery_id` 唯一索引保证外部 Delivery Inbox 只有一行；
2. `uk_project_activity_source(source_type, source_delivery_id)` 保证业务 Activity 只有一行。

成功处理事务把 Activity 写入和 Delivery `SUCCEEDED` 放在一起。若成功状态的版本条件失败，Activity
写入也回滚；后续重新处理时 Activity 唯一索引仍是最终保护。

## 14. 与 Redis Stream Pending、`XAUTOCLAIM` 和 DLQ 的对应

| DevPilot | Redis Stream 概念 | 作用与区别 |
|---|---|---|
| `dp_github_delivery` | Stream 中持久消息 | 保存可恢复的任务事实，介质分别是 MySQL 与 Redis |
| `RECEIVED` 扫描 | 消费组读取未分配消息 | 找到尚未开始的任务 |
| `PROCESSING + processing_started_at` | Pending Entry 和空闲时间 | 表示已被消费者取得但尚未完成 |
| 过期 PROCESSING 恢复 | `XAUTOCLAIM` | 重新取得失联消费者留下的任务 |
| `RETRY_WAIT + next_retry_at` | 延迟重试策略 | 控制下一次允许执行的时间 |
| `DEAD` | DLQ/死信 | 隔离不可恢复或超过上限的任务 |
| Activity 唯一索引 | 消费端幂等键 | 抵抗至少一次交付造成的重复业务执行 |

当前实现没有 Redis Stream，也没有真正的外部 DLQ；`DEAD` 是 Delivery 表中的终态。

## 15. 当前仍未实现的能力

本轮只完成自动恢复闭环，仍未实现：

- DEAD 的人工重放接口与权限校验；
- 人工重放和状态干预审计；
- 恢复扫描、积压、重试次数与 DEAD 数量指标；
- GitHub API 定时对账；
- Outbox 或消息队列；
- 多节点时对扫描频率和数据库压力的动态协调。

这些缺口不影响当前用 MySQL 条件更新实现的并发正确性，但上线前仍需要可观测性和受控人工处置能力。
