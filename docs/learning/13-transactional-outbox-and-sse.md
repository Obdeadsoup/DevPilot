# 第 13 节：Transactional Outbox 与 SSE

## 1. 要解决的双写问题

Task 分配、取消分配、提交 Review、退回、完成和重开都要产生即时通知。若先提交 Task、再调用 Notification，进程可能在两步之间崩溃；若先通知、后提交 Task，又可能通知了一个最终回滚的动作。`@TransactionalEventListener(AFTER_COMMIT)` 也只把事件放在当前 JVM 内存里，提交后到监听器执行前仍有丢失窗口。

本节使用 MySQL Transactional Outbox：Task、Status History、Project Activity 与 `dp_outbox_event` 在同一事务提交。Outbox 是可靠事实；提交后的 Spring Event 仅负责快速唤醒，周期扫描负责可靠兜底。

```text
Task 写事务 -> Task/History/Activity/Outbox -> COMMIT
                                            |-> AFTER_COMMIT 快速唤醒
                                            `-> Scanner 恢复遗漏
Outbox Worker -> Notification + mark PROCESSED（同事务）
Notification COMMIT -> AFTER_COMMIT -> 单实例 SSE（尽力发送）
```

## 2. Outbox 表与事件契约

`dp_outbox_event` 是本地事件 Inbox/Outbox 日志，保存确定性 `event_key`、聚合标识、白名单 `event_type`、`schema_version`、最小 JSON Payload 和处理状态。Payload 不保存 Task description、GitHub Body、凭据、Authentication、Java 类名或 `@class`。

六种 V1 事件为：

- `TASK_ASSIGNED_V1`
- `TASK_UNASSIGNED_V1`
- `TASK_SUBMITTED_FOR_REVIEW_V1`
- `TASK_CHANGES_REQUESTED_V1`
- `TASK_COMPLETED_V1`
- `TASK_REOPENED_V1`

Payload 固定保存 workspace/project/task、Task version、display key、安全标题快照、actor 和发生时间，并按事件保存负责人/原负责人/reporter 快照。异步处理不能从 Task 当前状态反推过去，因为 Task 可能已再次改动。

`event_key` 例如 `task:103:v7:assigned`。Task version 防同一聚合写入的竞争，`event_key` 唯一键防同一版本同一语义重复发布。重复 key 且事实相同视为幂等；事实不同是稳定冲突。`schema_version=1` 让 Payload 可以显式演进，而不依赖 Java 类型反射。

## 3. Handler Registry 与安全反序列化

Registry 以 `eventType + schemaVersion` 建立唯一白名单。重复 Handler 使启动失败；未知类型、不支持版本和 malformed JSON 都分类为不可重试错误并进入 DEAD。Notification Handler 只把已知类型反序列化为 `TaskInstantNotificationPayloadV1`，不会从 Payload 加载类名。

接收人规则为：Assigned 给 assignee；Unassigned 给 previous assignee；Submitted for Review 复用 `ProjectNotificationRecipientQuery` 查询 Manager；Changes Requested 给当时 assignee；Completed/Reopened 给 reporter 与 assignee 并去重。最终创建仍经过 Notification Application Service 的本地用户/范围校验。

## 4. 状态机、Claim 与恢复

```text
PENDING -> PROCESSING -> PROCESSED
                |-> RETRY_WAIT -> PROCESSING
                `-> DEAD
stale PROCESSING -> RETRY_WAIT 或 DEAD
```

- `PENDING`：与业务事务一起提交，尚未抢占。
- `PROCESSING`：某个 Worker 已用条件 UPDATE 抢占。
- `RETRY_WAIT`：瞬时失败，等待 `next_retry_at`。
- `PROCESSED`：可靠持久化副作用完成；不代表浏览器收到 SSE。
- `DEAD`：永久错误或有限重试耗尽。

扫描只发现候选，不等于获得所有权。Claim 使用 `id + status + expectedVersion` 条件 UPDATE，且 RETRY_WAIT 必须到期；成功更新一行才有处理权。两个实例可看到同一个 ID，但最多一个版本条件胜出。旧 Worker 持有的旧 version 不能覆盖恢复后的状态。

RetryPolicy 使用指数退避并受 `max-backoff` 封顶。瞬时数据库/Handler 故障可有限重试；未知类型、不支持 schema、坏 JSON、缺失必要 ID、稳定 scope 冲突直接 DEAD。错误表只保存稳定 code 与安全短消息，不保存堆栈、SQL 或 Payload。

Claim 后 JVM 崩溃会留下 PROCESSING。Recovery 扫描 `processing_started_at <= now - processing-timeout`，再以 status、version、cutoff 条件转 RETRY_WAIT；额度耗尽则 DEAD。test profile 关闭自动 Scheduler，测试直接调用服务。

## 5. 两个事务边界与两层幂等

第一条事务边界是 Task + History + Activity + Outbox。Publisher 使用 `MANDATORY` 加入调用方事务，不开 `REQUIRES_NEW`；序列化或 INSERT 失败会让业务动作整体回滚。

第二条事务边界是 Notification Handler + `markProcessed`。二者在 `OutboxDispatchService` 的同一事务中：若通知 INSERT 后状态更新冲突，通知也回滚。处理失败后，`markFailed` 才用独立事务记录 RETRY_WAIT/DEAD，避免失败状态跟随处理事务回滚。

两层唯一约束分别防不同问题：

1. `dp_outbox_event.event_key`：防业务事实重复发布；
2. `dp_notification(recipient_user_id, dedupe_key)`：防 Worker 重放造成业务通知重复。

如果通知已存在，`createIfAbsent` 返回 `ALREADY_EXISTS`，Handler 仍可完成并标记 Outbox PROCESSED。

## 6. 快速路径、扫描与有界执行器

Outbox INSERT 后发布只含 outboxId 的 `OutboxStoredSignal`。`AFTER_COMMIT` 保证只在事务成功后向专用有界 Executor 提交；它不创建 Notification，也不携带 Payload/Token。Executor 拒绝时不先 claim，记录仍为 PENDING/RETRY_WAIT，下一轮扫描可恢复。因此 Spring Event 决定延迟，数据库扫描决定可靠性。

当前模块化单体共享 MySQL，引入 Kafka/RabbitMQ/CDC 会增加运维和一致性面，第一版不需要。未来可由 CDC 或消息代理消费同一事件契约，但数据库仍应保留可靠发布语义。

## 7. SSE 是 Channel，不是事实来源

新 Notification 真正 INSERT 后，应用事务内发布只含 notificationId、recipientUserId、occurredAt 的 `NotificationCommittedEvent`。`AFTER_COMMIT` Listener 查询最新未读数并尽力向 SSE Registry 发送。重复 Notification 不发布事件；事务回滚不发送；IOException 只清理连接，不回滚 Notification 或 Outbox。

`GET /api/v1/notifications/stream` 必须带 Bearer Header。userId 只从 Principal 获取，不接受 recipient 参数或 query token。建立后返回 `connected` 和初始 unreadCount；新通知发送 `notification-created`，SSE `id` 为 notificationId，Data 只有 notificationId、unreadCount、occurredAt。客户端收到后重新查询 REST，数据库列表才是权威内容。

Registry 只保存 userId 和 `SseEmitter`，不保存 Token/Principal。一个用户支持多个标签页/设备；默认最多 5 个连接，超限完成并移除最旧连接。completion、timeout、error、send IOException 都清理。Heartbeat 是 comment，不落库、不经 Outbox，只保持连接并发现失效客户端。

断线重连不保证内存重放。前端应使用支持自定义 Authorization Header 的 Fetch-based SSE 客户端，指数退避重连，重连后立即查询通知列表和未读数，页面卸载时关闭连接。不要把长效 Token 放 URL：URL 更容易进入浏览历史、代理和访问日志。

当前 Registry 是单实例内存结构。若通知在实例 A 创建、连接在实例 B，实时提示可能缺失，但 Notification 已在数据库，可由 REST/重连补偿。后续可用 Redis Pub/Sub 做跨实例尽力广播，或用 RabbitMQ 承载更多消费者；本节均未实现，也不承诺 SSE 精确一次送达。

## 8. 配置与指标

`devpilot.outbox` 配置扫描间隔、批量、重试上限/退避、PROCESSING 超时和有界线程池；Duration 必须为正，batch 1～1000，重试 0～20，maxBackoff 不小于 initialBackoff。`devpilot.notification.sse` 配置启用、连接超时、Heartbeat 和单用户上限。

指标包括 published、processed、retry_wait、dead、processing_duration、deduplicated 与 SSE active_connections，只使用 eventType/result/failureType 等低基数标签，不使用 taskId、userId、eventKey。

## 9. 手动验证（PowerShell）

以下步骤在本地服务、MySQL/Redis 已启动后执行；把 Token 只存进当前 PowerShell 变量，不截图、不粘贴到工单或日志。

1. 调用登录 API，将返回的 access token 保存为 `$devpilotToken`。
2. 创建 owner/manager 与 assignee 两个本地用户。
3. 创建 Workspace、Project 和 Task，并分配 assignee。
4. 用下方 SQL 查询 Outbox，应看到 `TASK_ASSIGNED_V1`。
5. 等待扫描，确认状态变为 PROCESSED。
6. 以 assignee Bearer Token 查询 `/api/v1/notifications`。
7. 重放同一候选或重复扫描，确认通知不增加。
8. 将 Task 推进后提交 Review，确认 Manager 收到通知。
9. Manager Request Changes，确认当时 assignee 收到通知。
10. 完成 Task，再 Reopen，核对 reporter/assignee 去重规则。
11. 设置 `devpilot.outbox.enabled=false` 并重启，暂停自动扫描。
12. 再触发 Task 动作，确认留下 PENDING。
13. 恢复配置并重启，确认 Scanner 将其处理。
14. 在隔离测试库把一条事件置 PROCESSING，并把 started_at 调早于 timeout。
15. 运行 Recovery，确认转 RETRY_WAIT，随后处理成功。
16. 用支持 Header 的 Fetch SSE 客户端连接 `/api/v1/notifications/stream`。
17. 触发一个即时通知动作。
18. 观察 `notification-created`，再调用 REST 获取完整数据。
19. 主动断开 SSE。
20. 再触发通知，确认数据库仍有记录。
21. 重连后立即调用 REST，补回离线期间通知。
22. 同一账号打开多个标签页，确认均可收到提示。
23. 建立超过配置上限的连接，确认最旧连接被关闭。

```sql
SELECT id,event_key,event_type,processing_status,retry_count,next_retry_at,
       processing_started_at,processed_at,last_error_code,version
FROM dp_outbox_event ORDER BY id DESC;

SELECT id,recipient_user_id,notification_type,target_id,status,created_at,version
FROM dp_notification ORDER BY id DESC;
```

浏览器侧流程是：Bearer Header 建立 Fetch-based SSE；connected 初始化未读数；notification-created 后刷新列表；断线指数退避；重连后 REST 补偿；卸载页面关闭连接。当前仓库没有前端实现，因此本轮只提供契约，未做真实浏览器验证。

## 10. 下一步

下一节处理 DEAD 只读诊断、受控人工重放、一次性确认与操作审计。本节没有 Outbox 管理 HTTP API、人工重放、删除 DEAD、MQ、CDC、跨实例 SSE、邮件或企业 IM。
