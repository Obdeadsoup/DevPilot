# 第 12 节变更文件地图

## 模块与 Maven

```text
framework ← identity ← project ← task ← github
                    notification → identity/project/task/github
boot → all
```

根 POM 注册 `devpilot-notification` 与 dependencyManagement；boot 加依赖。上游无反向依赖。

## 文件地图

| 区域 | 文件/目录 | 职责 |
|---|---|---|
| Schema | `V11__add_reliable_notifications.sql` | 表、CHECK、Scope FK、唯一键与索引 |
| Task Port | `TaskReminderCandidate*`、`TaskReminderCandidateService`、`TaskMapper` | Due/Overdue/History 候选 |
| Project Port | `ProjectNotificationRecipientQuery/Service`、`ProjectMapper` | Manager、ACTIVE recipient 与 Scope |
| GitHub Port | `PullRequestReviewState*`、Service、PR Mapper | ACTIVE Link、current-head approval |
| Domain | `Notification*` enums、`NotificationDedupeKeyFactory` | 类型、状态、稳定 key |
| Write | `NotificationApplicationService`、Command/Result、Mapper | 短事务幂等创建与已读 |
| Scan | Properties、Scheduler、ScanService、RecipientResolver | fixedDelay 与五类规则 |
| API | Controller、DTO、QueryService | 当前用户列表、未读、read/read-all |
| Tests | notification unit tests、Boot smoke | key、接收人、规则装配 |
| Docs | learning 12、本文件、README/architecture/database/roadmap | 真实能力与边界 |

## 调用链

- Due Soon/Overdue：Scheduler → ScanService → Task Port → recipient fallback → createIfAbsent → INSERT。
- Escalation：Task Port → Project Manager Port → 每位 recipient 独立 INSERT。
- Task Review：History 最近 SUBMITTED_FOR_REVIEW → assignee+Managers → INSERT。
- PR Review：Task Review 候选 → GitHub Port → OPEN/non-draft/current-head 未批准 → INSERT。
- Query/Read：Controller → CurrentUserProvider → Query/Application → recipient-scoped Mapper。

## Dedupe、事务与索引

dueAt、reviewStart、Head SHA 都属于 key 的业务事实；当前扫描时间不属于。每条通知短事务，DuplicateKey 正常
返回 ALREADY_EXISTS。关键索引是 recipient/status/created、scope/created、source 与唯一
recipient/dedupe。无 Redis 锁、无长扫描事务。

## 关键 Diff 与阅读顺序

先读 V11 与 DedupeKeyFactory，再读三个上游 Port/SQL、ScanService、ApplicationService/Mapper，最后读 API、
配置与测试。DTO/Entity 是机械投影，可暂时跳过。重点对照 TaskMapper 的 History 子查询、PR Mapper 的
`APPROVED + commit_sha=head_sha`、Mapper 的 recipient/version 条件和 DuplicateKey 路径。
