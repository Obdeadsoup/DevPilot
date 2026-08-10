# 第 14 节文件地图：DEAD Replay 与 Audit

## 完整清单

- `[MOD]` `pom.xml`、`devpilot-boot/pom.xml`：注册/装配 audit 模块。
- `[MOD]` `AGENTS.md`：补充模块与依赖方向。
- `[MOD]` `.gitignore`：允许第 14 节文档。
- `[NEW]` `devpilot-audit/pom.xml`。
- `[NEW]` `devpilot-audit/src/main/java/.../audit/api/*`：三个管理 Controller。
- `[NEW]` `.../audit/application/*`：查询、鉴权、reason/policy、Replay 事务、Audit 事务与提交后唤醒。
- `[NEW]` `.../audit/domain/*`：受控枚举、命令、响应、分页与 Replay DTO。
- `[NEW]` `.../audit/error/AuditErrorCode.java`。
- `[NEW]` `.../audit/event/GitHubSyncReplayCreatedSignal.java`。
- `[NEW]` `.../audit/persistence/entity/*`、`.../persistence/mapper/*`。
- `[NEW]` `devpilot-audit/src/test/java/.../*Test.java`：Policy、reason、sequence、metadata 白名单。
- `[NEW]` `V13__add_dead_replay_and_audit.sql`。
- `[MOD]` `GitHubSyncTriggerType.java`：加入 MANUAL_REPLAY。
- `[MOD]` `DevPilotApplicationTests.java`、`IsolatedPersistenceTestConfiguration.java`：模块装配 Smoke。
- `[MOD]` `README.md`、`docs/architecture.md`、`docs/database-design.md`、`docs/capability-coverage-and-roadmap.md`。
- `[NEW]` 本文件与 `docs/learning/14-dead-replay-and-audit.md`。
- `[DEL]` 无。

## 模块依赖

```text
boot → audit
audit → framework + identity + project + github + outbox
project/github/outbox -X→ audit
```

Audit 是末端运维编排模块，上游业务模块无反向依赖。

## 数据与索引

V13 新增 append-only `dp_audit_log` 及 scope/actor/resource/action/result 时间索引。Outbox 与 Sync Run 增加 replay_of、sequence、requestedBy、reason；`UNIQUE(replay_of,sequence)` 保证序号，既有 event_key 和开放 Sync Run 唯一索引继续仲裁并发。Sync trigger CHECK 扩展 MANUAL_REPLAY。

```text
original DEAD id=41
├─ replay id=52, replay_of=41, sequence=1
└─ replay id=67, replay_of=41, sequence=2（前一轮闭合后才允许）
```

## 调用链

```text
Outbox DEAD Query
Controller → DeadLetterQueryService → ReplayAuthorizationService
           → DeadLetterMapper(SQL workspace+project scope)

Outbox Replay
Controller → OutboxReplayApplicationService → auth → reason
           → OutboxReplayTransactionService → FOR UPDATE/version/policy/open check
           → INSERT new PENDING → SUCCESS Audit → COMMIT → OutboxStoredSignal → original Worker

GitHub Sync Replay
Controller → GitHubSyncReplayApplicationService → REPOSITORY_UPDATE → reason
           → GitHubSyncReplayTransactionService → FOR UPDATE/version/open check
           → INSERT MANUAL_REPLAY PENDING → SUCCESS Audit → COMMIT
           → GitHubSyncReplayCreatedSignal → original Dispatcher/Worker

Failure / Denied
ApplicationService catches stable error → AuditRecorder.recordFailure
           → REQUIRES_NEW INSERT FAILURE/DENIED → rethrow original stable error
```

Replay Service 不写 Checkpoint，也不直接写 Notification/GitHub 最终表。原 Worker 的 overlap、RetryPolicy 与业务唯一键保持有效。

## 权限矩阵

| 能力 | OWNER/ADMIN | PROJECT_ADMIN | DEVELOPER | VIEWER/MEMBER |
|---|---|---|---|---|
| Outbox DEAD/Replay | Workspace 内 | 本项目 | 拒绝 | 拒绝 |
| Sync DEAD/Replay | Workspace 内 | 本项目 | REPOSITORY_UPDATE 可用 | 拒绝 |
| Audit 查询 | Workspace | 必须 projectId | 拒绝 | 拒绝 |

## Audit 与敏感数据

Audit Mapper 只有 insert/query。成功与 Replay 同事务；失败/拒绝为 REQUIRES_NEW。metadata 使用固定白名单，DEAD Response 不暴露 payload。现有项目没有 Correlation ID，故本轮字段为 NULL，未增加伪 Trace 链路。

## version 与关键 Diff

- 原 DEAD 只锁定读取，不 UPDATE；expectedVersion 不匹配返回 409。
- 原行锁串行化同一来源 Replay；唯一索引处理最终竞态。
- Outbox 新 event_key 与原 Payload 业务幂等语义分离。
- Sync 新 Run 为 MANUAL_REPLAY，不回退可靠 Checkpoint。
- SQL 在数据库层直接应用 workspace/project/binding scope。

## 推荐阅读顺序

1. V13 migration
2. Audit domain 与 ErrorCode
3. AuditApplicationService / AuditRecorder
4. ReplayReasonValidator / ReplayableOutboxEventPolicy
5. OutboxReplayApplicationService 与事务服务
6. GitHubSyncReplayApplicationService 与事务服务
7. DeadLetterQueryService / DeadLetterMapper
8. AuditQueryService / AuditLogMapper
9. Controllers
10. Tests

DTO/Entity 可先跳过；重点测试对应 Policy、reason、sequence、metadata 白名单、模块装配，以及 Docker 可用时的全套 Testcontainers regression。
