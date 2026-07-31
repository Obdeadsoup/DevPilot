# 第 2 节：GitHub Webhook 可靠接收链路加固

## 1. Webhook 与轮询

Webhook 是外部系统主动推送事件：GitHub 在仓库发生变化时向 DevPilot 的
`POST /api/v1/github/webhooks` 发起请求。它延迟低、无效请求少，但接收方必须处理伪造请求、
重复投递、并发投递、进程故障和下游处理失败。

轮询是 DevPilot 定期调用 GitHub API 查询变化。它更适合对账和补偿，但存在发现延迟，
还会消耗 API 限额。两者不是互斥关系：Webhook 负责实时性，后续阶段的定时 API 对账负责完整性。
当前代码只实现了 Webhook 垂直链路，尚未实现 GitHub API Client 和定时对账。

## 2. 三个关键请求头

- `X-Hub-Signature-256`：GitHub 使用共享 Secret 对原始请求体计算出的 HMAC-SHA256，
  接收方用它验证请求体没有被篡改且发送方持有同一 Secret。
- `X-GitHub-Delivery`：一次投递的外部唯一标识。DevPilot 将它保存为
  `github_delivery_id`，作为接收幂等键。
- `X-GitHub-Event`：事件类型。当前 `GitHubWebhookService` 只接受 `ping` 和 `push`，
  后续处理器据此选择解析逻辑。

这三个 Header 缺失时，Controller 仍会把请求交给应用服务，由
`GitHubWebhookService.requireHeader` 统一返回 `GITHUB_0400`。

## 3. 为什么必须使用原始 `byte[]` 验签

HMAC 的输入是字节序列，不是“语义相同的 JSON 对象”。即使字段值完全相同，改变空格、换行、
字段顺序或字符编码也会得到不同的 HMAC。若先反序列化成对象再序列化，服务端很可能已经改变了
GitHub 实际签名的字节。

因此 `GitHubWebhookController` 以 `byte[] rawBody` 接收请求体，并把同一数组传给
`GitHubWebhookSignatureVerifier.verify`。Verifier 直接对这些字节计算 HMAC-SHA256，
再使用 `MessageDigest.isEqual` 比较期望值与 Header 中的值。Payload 解析可以读取同一份字节，
但不能用解析后重新生成的 JSON 代替验签输入。

## 4. SHA-256 与 HMAC-SHA256

SHA-256 是不带密钥的摘要算法。当前代码用它计算 `payload_sha256`，目的不是证明请求来自 GitHub，
而是得到 Payload 的稳定指纹。本轮重复 Delivery 校验比较
`repositoryId + eventType + payloadSha256`，不比较完整 Payload 字符串。

HMAC-SHA256 是带 Secret 的消息认证码。只有持有 Secret 的双方才能为指定 Payload 生成正确结果，
所以它用于来源认证和完整性校验。简单地保存一个 SHA-256 Hash 不能替代 HMAC 验签。

## 5. `webhook_secret_ref` 与真实 Secret 分离

V6 将旧 `credential_ref` 原地重命名为 `webhook_secret_ref`。该列只保存一个受限格式的环境变量名，
而不保存明文
Webhook Secret。`EnvironmentWebhookSecretResolver` 只接受匹配
`DEVPILOT_GITHUB_WEBHOOK_SECRET_[A-Z0-9_]+` 的引用，再从 Spring `Environment` 中解析真实值。

`GitHubWebhookService` 只把解析出的 Secret 传给 Verifier。当前日志不会输出 Secret、Token 或原始
私有 Payload；Worker 失败日志仅记录内部 Delivery ID 和异常类型。这样数据库泄露不会直接暴露
Webhook Secret，凭据也可以独立轮换。新增的 `api_credential_ref` 只供 GitHub REST API 使用，Webhook
验签不会读取它。

## 6. Delivery 表是外部事件 Inbox

`dp_github_delivery` 是 GitHub 外部事件进入 DevPilot 后的 Inbox。请求线程完成仓库定位和验签后，
先把外部 Delivery ID、事件类型、原始 Payload、Payload Hash、接收时间和 `RECEIVED` 状态落库，
再快速响应并异步处理。

Inbox 把“已安全接收”与“业务处理完成”分开。即使 Activity 尚未生成，系统仍有一份可追踪的
原始事件和处理状态。当前 Payload 会保存在 MySQL JSON 列中，因此日志不需要打印 Payload。

## 7. 为什么唯一索引比先查后插可靠

如果两个请求先执行 `SELECT`，它们都可能看到“尚不存在”，随后都执行 `INSERT`。这就是典型的
检查与使用之间竞态。应用层先查无法成为并发下的最终保证。

V1 Flyway 在 `github_delivery_id` 上建立
`uk_github_delivery_external_id`。MySQL 在并发写入时只允许一个事务成功，另一个收到
`DuplicateKeyException`。`GitHubWebhookService` 捕获异常后读取已存在记录，并比较
`repositoryId`、`eventType` 和 `payloadSha256`：

- 三项完全一致：这是同一事件的正常重投，返回 `duplicate=true`；
- 任一项不同：同一 Delivery ID 指向了不同事实，返回冲突 `GITHUB_0502`；
- 已有记录不会被新 Payload 覆盖。

唯一索引负责“只能有一行”，三字段比较负责“重复请求是否真的是同一事件”。

## 8. `202 Accepted` 的准确语义

首次有效投递返回 `202 Accepted`，表示请求已经通过校验并被持久化，系统接受了后续异步处理责任；
它不表示 Project Activity 已经生成，也不表示 Delivery 已经是 `SUCCEEDED`。

完全一致的重复投递返回 `200 OK` 和 `duplicate=true`，表示已有相同 Delivery，服务端无需再次入队。
冲突的重复 Delivery 返回 `409 Conflict`，而不是伪装成幂等成功。

## 9. `AFTER_COMMIT + Async` 的执行时机

`GitHubWebhookService.receive` 在事务中插入 Delivery，并发布
`GitHubDeliveryReceivedEvent`。`GitHubDeliveryEventListener` 使用
`@TransactionalEventListener(phase = AFTER_COMMIT)`，所以只有接收事务成功提交后才会调用监听器；
回滚事务不会触发处理。这样 Worker 查询时能看到已提交的 Delivery。

监听器同时使用 `@Async("githubDeliveryTaskExecutor")`，实际处理在线程池执行，不占用 Webhook
请求线程。时序是：

```text
请求线程：验签 → INSERT Delivery → 发布进程内事件 → 提交事务 → 返回 202
线程池：                                          监听事件 → Worker 处理
```

异步线程与 HTTP 响应谁先完成不应作为业务保证；准确保证只是业务处理发生在提交之后。

## 10. Delivery 与 Project Activity 两层幂等

第一层是 Inbox 幂等：`dp_github_delivery.github_delivery_id` 唯一，防止同一 GitHub 投递生成多条
Delivery。

第二层是业务幂等：`dp_project_activity` 的
`uk_project_activity_source(source_type, source_delivery_id)` 唯一。
`ProjectActivityMapper.insertIfAbsent` 使用 `INSERT ... ON DUPLICATE KEY UPDATE id = id`，
即使 Worker 因故再次处理同一 Delivery，也不会生成第二条 Activity。

第一层减少重复处理，第二层保护最终业务结果。只依赖其中一层都会留下更大的故障面。

## 11. `version + 条件 UPDATE` 的抢占原理

`GitHubDeliveryStateService.claim` 先读取 Delivery 及其 `version`，然后执行条件更新：

```sql
UPDATE dp_github_delivery
SET processing_status = 'PROCESSING',
    version = version + 1
WHERE id = ?
  AND processing_status IN ('RECEIVED', 'RETRY_WAIT')
  AND version = ?
```

多个 Worker 同时读取相同版本时，只有一个能把该版本更新成功；其余更新行数为 0，放弃处理。
这把状态条件和乐观锁版本放在同一个原子 SQL 中，不需要分布式锁。成功抢占后重新查询记录，
得到递增后的版本，供 `PROCESSING → SUCCEEDED` 的条件更新使用。

## 12. 成功处理事务与失败记录独立事务

`GitHubDeliveryProcessingService.process` 使用一个事务执行：

1. `ProjectActivityService.recordGitHubActivity` 写入 Activity；
2. 按当前状态和版本把 Delivery 标记为 `SUCCEEDED`。

若标记成功发生冲突，异常会使 Activity 写入和成功状态一起回滚，避免“Activity 已提交但 Delivery
仍显示处理中”。

Worker 捕获处理异常后调用 `GitHubDeliveryStateService.markFailed`。该方法使用
`REQUIRES_NEW`，失败记录在一个独立事务中提交，不依赖已经失败并回滚的处理事务。当前实现只会标记
`FAILED`，尚未实现失败后的重试调度。

## 13. 当前 Spring Event 的崩溃丢失窗口

当前事件总线是 Spring 进程内事件，不是可靠消息系统。存在如下窗口：

```text
Delivery 数据库提交成功
→ 进程内事件等待异步线程执行
→ 应用在 Worker 抢占前崩溃
```

数据库中会留下 `RECEIVED` Delivery，但内存事件已经消失。应用重启不会自动重新发布该事件，
当前也没有数据库扫描器寻找滞留记录。因此“外部事件已落库”是可靠的，但“每条已落库事件最终一定被
处理”目前还没有闭环。

线程池队列已满、异步任务被拒绝、进程强制退出也会暴露类似问题。`AFTER_COMMIT` 解决的是事务可见性，
不是消息持久化。

## 14. 下一阶段为什么需要扫描、有限重试和 `DEAD`

下一阶段需要以 Delivery Inbox 为事实来源，定期扫描：

- 长时间停留在 `RECEIVED` 的记录；
- 到达重试时间的 `RETRY_WAIT` 记录；
- 必要时识别超时停留在 `PROCESSING` 的记录。

重试必须有次数上限和退避时间，避免永久失败事件形成热循环；超过上限后进入 `DEAD`，再由受控的人工
重放和审计处理。扫描与 Worker 仍应复用现有条件抢占及两层幂等。

这些能力在当前代码中尚未实现。本节没有加入定时扫描器、重试调度器或 `DEAD` 流转，只说明为何它们是
补齐崩溃恢复语义所需的下一阶段工作。

## 15. 与黑马点评 Redis Stream 秒杀链路的对应关系

黑马点评秒杀链路通常先在请求入口完成资格与库存判断，再把订单消息写入 Redis Stream；消费者组异步
读取、执行数据库事务并确认消息，Pending List 用于恢复未确认消息。DevPilot 可以按职责建立如下对应：

| DevPilot 当前链路 | Redis Stream 秒杀链路 | 共同目的与差异 |
|---|---|---|
| Webhook Header 校验与 HMAC 验签 | 秒杀入口参数、资格校验 | 在进入异步链路前拒绝无效请求；认证手段和业务规则不同 |
| `dp_github_delivery` Inbox | Redis Stream 中的订单消息 | 保存待异步处理的事实；前者在 MySQL，后者在 Redis Stream |
| Delivery ID 唯一索引 | 一人一单等幂等约束 | 用最终存储约束抵抗并发重复 |
| `AFTER_COMMIT` Spring Event | `XADD` 后消费者组读取 | 唤醒异步消费者；Spring Event 不持久化，可靠性弱于 Stream 消息 |
| Worker 条件抢占 | 消费者组对消息的消费归属 | 防止多个消费者重复执行同一任务 |
| Activity 唯一索引 | 订单表业务唯一约束 | 即使消息重复处理，最终业务结果仍只写一次 |
| 未来扫描 `RECEIVED/RETRY_WAIT` | Pending List 重读 | 恢复“已保存但未完成”的任务；DevPilot 当前尚未实现 |
| 未来有限重试与 `DEAD` | 重试上限与死信/人工介入 | 隔离持续失败事件；DevPilot 当前尚未实现 |

最关键的区别是：Redis Stream 的消息本身是可恢复的，消费者确认前可进入 Pending List；当前 DevPilot
只把 Delivery 持久化，唤醒信号仍是易失的 Spring Event。好消息是 Delivery Inbox 已经保留了恢复所需的
事实，下一阶段可以通过数据库扫描补上这个缺口，而不必在当前阶段提前引入消息队列。
