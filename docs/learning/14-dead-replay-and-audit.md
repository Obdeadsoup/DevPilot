# 第 14 节：DEAD 治理、人工 Replay 与 Audit

## 1. DEAD 的真实语义

`DEAD` 表示自动 Retry 已停止，需要人判断故障是否已修复、事件是否仍应执行。它不是“可删除垃圾”，而是必须保留的故障证据：原 Payload、尝试次数、最后错误、完成时间和 version 都留在原行。

## 2. Retry 与 Replay

Retry 是状态机按 RetryPolicy 对同一条记录自动再次执行；Replay 是管理员给出 reason 后创建一条新记录的人工运维动作。Replay 可能再次进入原有限 Retry，最终也可能再次 DEAD，不能称为精确一次。

## 3. 为什么不复活原 DEAD

直接把原行 `UPDATE ... SET status='PENDING'` 会混合自动尝试和人工尝试、丢失不可逆历史，并重新激活旧 version。本实现只锁定并读取原 DEAD，创建新的 PENDING 行，原行不 UPDATE。

新行通过 `replay_of_event_id/replay_of_run_id` 指向原 DEAD，`replay_sequence` 是该原记录已有子 Replay 最大序号加一。Outbox 使用新 `event_key=manual-replay:outbox:{originalId}:{sequence}`；Payload 和其中的业务幂等键不允许客户端修改。

## 4. Reason 与 Replay 幂等

reason trim 后必须为 10～500 字符，`retry`、`test`、单独标点等无意义内容拒绝。reason 同时存入 Replay 与 Audit，不进入 Notification 或 Project Activity。

Outbox 对原行 `SELECT ... FOR UPDATE`，然后检查开放 Replay，并由 `(replay_of_event_id,replay_sequence)` 与新 event_key 唯一键兜底。GitHub Sync 还沿用 Binding + Resource 的开放 Run 唯一索引。version 必须匹配 `expectedVersion`。这些防线保证并发请求最多创建一个开放 Replay。

Replay 仍走原 Worker 和应用服务。Outbox → Notification 继续依赖 `(recipient_user_id,dedupe_key)`；GitHub Commit 依赖 Repository + SHA，Issue/PR/Review 依赖 stable ID 与快照 upsert，Activity 依赖来源唯一键。Replay 不直接写最终业务表。

## 5. GitHub Sync Replay 与 Checkpoint

人工创建 `trigger_type=MANUAL_REPLAY` 的新 PENDING Run，复制 Binding 和 Resource Type，不接受客户端 since/cursor。Replay 服务完全不更新 Checkpoint；原 Worker 从最后可靠 Checkpoint 计算 since，并继续应用 overlapWindow。少量重复读取由业务唯一键消化，成功后才按原事务推进 Checkpoint。

## 6. 三类历史的边界

- Task Status History：Task 从一个业务状态流转到另一个状态，是业务时间线。
- Project Activity：项目成员可见的协作事件。
- Audit Log：谁在何种权限范围内执行了什么高风险运维动作，以及 SUCCESS、FAILURE 或 DENIED；不进入普通 Activity。

## 7. Audit 事务边界与 append-only

成功 Replay 的 `REQUESTED/CREATED SUCCESS Audit` 与新 Replay 位于同一事务：Replay 能看见时，成功 Audit 必然存在。权限拒绝、非法 reason、非 DEAD、version 冲突等没有成功业务事务，却仍需留下事实，因此 `recordFailure` 使用 `REQUIRES_NEW`。若 Audit 数据库写入失败，高风险 Replay 默认 fail closed。

`dp_audit_log` 没有 updated_at、version 或逻辑删除；Mapper 只暴露 INSERT 与 scope SELECT，没有 UPDATE/DELETE。未来可以增加独立数据库权限、WORM 或外部归档，本节未实现。

## 8. Scope 与 RBAC

| 操作 | Workspace OWNER/ADMIN | PROJECT_ADMIN | DEVELOPER | MEMBER/VIEWER |
|---|---:|---:|---:|---:|
| Outbox DEAD 列表/详情/Replay | 允许 | 本 Project 允许 | 拒绝 | 拒绝 |
| Sync DEAD 列表/Replay | 允许 | 允许 | 有 REPOSITORY_UPDATE 时允许 | 拒绝 |
| Workspace Audit | 允许 | 必须显式 projectId 且仅本 Project | 拒绝 | 拒绝 |

授权之外，DEAD/Audit Mapper 的 SQL 直接带 workspaceId + projectId，Sync 还带 bindingId，不通过裸 ID 取回后再过滤。

## 9. 安全 metadata

Audit metadata 只允许 `originalStatus/newReplayId/originalAttemptCount/eventType/syncResourceType/bindingId/replaySequence`。白名单之外直接拒绝，尤其不保存 Outbox/Webhook Payload、Issue/PR Body、Task description、Authorization、Token、PAT、Secret、密码、SQL 或 Java stack trace。DEAD API 也只返回错误摘要，不返回 payload_json。

## 10. Correlation ID 决策

现有代码没有 Request/Correlation ID 基础设施。本节将它保留为可选项，没有为了 Audit 单独引入 ThreadLocal/MDC 传播；`request_id/correlation_id` 字段先允许 NULL。后续 Observability 统一设计请求、异步传播和指标链路，不能把当前状态描述成 OpenTelemetry Trace。

## 11. API 与执行时机

Outbox：

```text
GET  /api/v1/workspaces/{w}/projects/{p}/operations/outbox/dead
GET  /api/v1/workspaces/{w}/projects/{p}/operations/outbox/{eventId}
POST /api/v1/workspaces/{w}/projects/{p}/operations/outbox/{eventId}/replays
```

GitHub Sync 与 Audit：

```text
GET  /api/v1/workspaces/{w}/projects/{p}/github-repositories/{binding}/sync-runs?status=DEAD
POST /api/v1/workspaces/{w}/projects/{p}/github-repositories/{binding}/sync-runs/{run}/replay
GET  /api/v1/workspaces/{w}/audit-logs
GET  /api/v1/workspaces/{w}/audit-logs/{auditId}
```

Replay 成功返回 `202 Accepted`，含新 Replay ID 和 PENDING；这只表示已可靠接收，非处理成功。COMMIT 后 Outbox 发布原快速唤醒 Signal，Sync 发布快速调度 Signal；即使 JVM 在唤醒窗口崩溃，原数据库恢复扫描仍会发现 PENDING。

## 12. Local/Test 故障演练

1. 仅在 Local/Test 让受控测试 Handler 持续失败，等待 Outbox 达到 DEAD；不要修改生产 Payload。
2. 管理员调用 DEAD 列表与详情，确认响应没有 payload。
3. 修复 Handler 后，以当前 version 和不少于 10 字的 reason 发起 Replay。
4. 查询数据库，确认原 DEAD 的 status/version/错误未变化，新行是 PENDING 且 replay_of、sequence、requestedBy、reason 正确。
5. 等待原 Worker 处理新行，确认它进入 PROCESSED；通过 Notification 唯一键确认没有重复通知。
6. 用 Developer/Viewer 调用 Outbox Replay，确认 403 和 DENIED Audit。
7. 对 DEAD Sync Run 发起 Replay，确认原 Run 保留、新 Run 为 MANUAL_REPLAY，Checkpoint 未回退。
8. 使用旧 expectedVersion，确认稳定 409 与 FAILURE Audit。
9. 以 Workspace 管理员或显式 Project Admin filter 查询 Audit。

诊断 SQL：

```sql
SELECT id,event_key,event_type,processing_status,retry_count,last_error_code,version,
       replay_of_event_id,replay_sequence
FROM dp_outbox_event ORDER BY id DESC;

SELECT id,actor_type,actor_user_id,workspace_id,project_id,action_type,resource_type,
       resource_id,result,reason,error_code,occurred_at
FROM dp_audit_log ORDER BY id DESC;
```

演练不要求也不允许暴露 Token、PAT、Secret 或 Payload。

## 13. 当前未实现与下一节

未实现 Delivery 专用 Replay API、任意时间范围 Backfill、Payload 编辑、通用后台、自动修复 DEAD、MQ、Audit WORM/外部归档、Correlation Filter、OpenTelemetry 和 Agent Tool Audit。下一节进入 Metrics / Health / Backlog、Prometheus-ready 指标、处理 SLO 与故障演练。
