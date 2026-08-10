# 第 13 节文件地图：Transactional Outbox 与 SSE

## 调用链与边界

```text
assign/unassign ─┐
submit/review/...├─> TaskOutboxEventFactory -> DatabaseOutboxEventPublisher
                 │     [Task + History + Activity + Outbox 同事务]
                 └─> COMMIT -> OutboxStoredSignalListener -> bounded executor
                                      ^
Scheduler -> pending/due scan ----------|（可靠恢复）
Worker -> claim(status+version) -> Dispatch transaction
       -> TaskNotificationOutboxHandler -> Notification createIfAbsent
       -> markProcessed                  [同事务]
Notification COMMIT -> NotificationSsePublisher -> per-user SseEmitter(s)
SSE 断线/跨实例遗漏 -> Notification REST 查询补偿
```

状态机：`PENDING -> PROCESSING -> PROCESSED`，失败走 `RETRY_WAIT -> PROCESSING` 或 `DEAD`，stale PROCESSING 由 Recovery 转 RETRY_WAIT/DEAD。

两层幂等：Outbox `UNIQUE(event_key)` 防同一 Task version/action 重复发布；Notification `UNIQUE(recipient_user_id,dedupe_key)` 防重复处理对同一用户重复落库。

三个事务边界：Task 与 Outbox 同事务；Handler Notification 与 markProcessed 同事务；SSE 仅在 Notification commit 后尽力发送，失败不回滚数据库。

## [NEW]

| 文件 | 分层/职责 | 调用与关键点 | 风险与测试 |
|---|---|---|---|
| `OutboxStateService` | application | claim/PROCESSED/failure/stale 的条件 UPDATE | status+version；state integration test |
| `OutboxDispatchService` | application | Handler 与 markProcessed 同事务 | 状态冲突须回滚副作用 |
| `OutboxWorker` | application | claim 后处理，失败独立记录 | 不记录 Payload；state/handler tests |
| `OutboxRecoveryService/Scheduler` | application | 扫描 PENDING/due/stale，数据库可靠兜底 | 重复扫描、卡死恢复；integration tests |
| `OutboxStoredSignal/Listener` | event/application | AFTER_COMMIT 快速提交 ID 到有界池 | 拒绝不 claim；Scanner 补偿 |
| `TaskInstantEventType/PayloadV1/TaskOutboxEventFactory` | task application | 六类快照事件、确定性 key、最小 Payload | 不保存 description/凭据；factory test |
| `TaskNotificationOutboxHandler/Configuration` | notification application/config | 六个显式 Handler，复用 Manager 查询，幂等创建通知 | 接收人和 scope；handler tests |
| `NotificationCommittedEvent` | notification event | 只携带 notificationId/recipient/time | 仅 CREATED 发布 |
| `NotificationSseProperties` | notification config | timeout/heartbeat/连接上限校验 | test profile 禁用调度 |
| `NotificationConnectedSseData/NotificationCreatedSseData` | notification SSE DTO | 最小 SSE Envelope | 不含正文/dedupe/token |
| `NotificationSseRegistry` | notification SSE | userId -> 多 emitter、淘汰最旧、清理与指标 | 并发、IOException、单实例；registry tests |
| `NotificationSsePublisher` | notification SSE | AFTER_COMMIT 查询未读数并尽力 send | 失败不影响 DB；SSE tests |
| `NotificationSseHeartbeatScheduler` | notification SSE | comment heartbeat | 不落库、不走 Outbox |
| `NotificationStreamController` | notification API | authenticated Principal 建立 stream、发送 connected | 禁止 query token/recipient；security tests |
| `OutboxRetryPolicyTest`、`OutboxFailureAndRegistryTest` | outbox tests | 重试、分类、注册安全 | 纯单元测试 |
| `TaskOutboxEventFactoryTest` | task test | key/schema/最小 Payload | 敏感字段断言 |
| `TaskNotificationOutboxHandlerTest` | notification test | assignee/manager/去重 | Mockito |
| `NotificationSseRegistryTest` | notification test | 多用户、多连接与上限 | 无 Thread.sleep |
| `NotificationSsePublisherTest` | notification test | AFTER_COMMIT Channel 异常不得外溢 | Notification/Outbox 结果不受 SSE 失败影响 |
| `OutboxStateMachineIntegrationTest` | boot Testcontainers | 并发 claim、due、DEAD、stale、旧 version | Docker 不可用时明确跳过 |
| `TaskOutboxTransactionIntegrationTest` | boot Testcontainers | 六动作同事务事件、冲突整体回滚、资料更新不发事件 | Docker 不可用时明确跳过 |
| `13-transactional-outbox-and-sse.md` | learning | 原理、边界、23 步演练 | 不把未来能力写成已实现 |
| 本文件 | changes | 全局导航和关键 Diff | 随代码同步 |

## [MOD]

| 文件 | 修改职责与关键方法 | 边界/测试 |
|---|---|---|
| 根 `pom.xml` | 将既有 outbox 模块纳入 reactor/dependencyManagement，并按依赖方向排序 | task 不依赖 notification；Maven reactor |
| `.gitignore` | 放行第 13 节文档 | Git status |
| `TaskApplicationService` | assign/unassign 更新与 Activity 后发布快照 | 同一外层事务、expectedVersion 不变 |
| `TaskWorkflowService` | submit/requestChanges/complete/reopen 在 History/Activity 后发布 | 非通知流转不发布 |
| `NotificationType/DedupeKeyFactory/Mapper` | 六类型、Task 即时 dedupe、按 key 回查 | recipient+dedupe unique |
| `NotificationApplicationService` | 仅 CREATED 时事务内发布 committed event | 回滚不 SSE，duplicate 不重复推 |
| `NotificationQueryService` | 按 recipient 内部查询未读数 | SSE 不依赖异步 SecurityContext |
| `NotificationConfiguration` | 启用 SSE properties | 配置校验 |
| `SecurityConfiguration` | `/api/v1/notifications/**` authenticated | Stateless Bearer，不开放 query token |
| `IsolatedPersistenceTestConfiguration` | mock Outbox Mapper | 隔离 Context 不访问 DB |
| `DevPilotApplicationTests` | 断言 Publisher/Worker/Registry 装配 | Module Assembly Smoke |
| `AuthenticationSecurityIntegrationTest` | stream 401、Bearer connected 契约 | MySQL+Redis Testcontainers |
| `README.md`、三份架构文档 | 能力与未实现边界 | 文档回归 |
| `DatabaseOutboxEventPublisher` | 重复 eventKey 的 Payload 改为 JSON 结构比较 | MySQL JSON 格式差异不误报冲突 |

## [DEL]

无。

## [BASELINE/VERIFIED]

当前基线 `4782431` 已包含且本轮未改动：`AGENTS.md`、V12 migration、`devpilot-outbox/pom.xml`、
boot/task/notification 子模块 POM、Outbox Envelope/Status/Handler/RetryPolicy/Publisher port、FailureClassifier、
Properties/Configuration、Entity/Mapper，以及 application/test profile 的 Outbox/SSE 配置。本轮完整阅读并验证这些
前置实现；它们不是当前 working tree 的新增 Diff。

## 关键 Diff 导读

1. 从 V12 看持久化状态和索引，而不是先从 Scheduler 看。
2. `TaskOutboxEventFactory` 展示业务快照及确定性 key；两个 Task Service 展示事务接入点。
3. `DatabaseOutboxEventPublisher` 的 MANDATORY 是业务/Outbox 原子性的关键。
4. Mapper `claim/markProcessed/markFailure/recoverStale` 的 WHERE 条件体现并发所有权。
5. `OutboxDispatchService` 的单事务包住 Handler 与 PROCESSED。
6. Notification Handler 展示第二层幂等和接收人规则。
7. `NotificationApplicationService -> NotificationCommittedEvent -> NotificationSsePublisher` 展示提交后尽力推送。
8. Registry 展示多连接、上限、清理；Controller 展示 Principal 范围和 Bearer 安全。

## 推荐阅读顺序

1. V12 migration
2. Envelope / Status / RetryPolicy
3. Publisher 与 Task Factory/Service 接入
4. Mapper / StateService
5. Worker / Dispatch / Recovery
6. Notification Handler
7. NotificationApplicationService 与 committed event
8. SSE Registry / Publisher / Controller
9. 单元和 Testcontainers 测试

初读可暂时跳过 Entity getter/setter、SSE data record、配置 record 的机械字段投影；不能跳过 Mapper 条件 SQL、事务传播、失败分类和安全反序列化。
