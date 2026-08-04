# 第 11 节：本地 Task 状态机与 GitHub 显式关联

## Task 与外部 Snapshot

Task 是 DevPilot 本地的计划、负责人、截止时间和状态事实；GitHub Issue/PR 是外部仓库的当前快照。
Issue CLOSED、PR MERGED、Review APPROVED、GitHub Assignee 或 Label 都不会自动改变 Task。外部状态只作为
提示；未来 Agent/规则引擎只能提出 Proposal，正式动作仍必须经过 `TaskWorkflowService`。

`devpilot-task` 依赖 framework、identity、project，绝不依赖 github。Task 定义
`TaskGitHubReferenceReader` 与中立 `TaskExternalReferenceSnapshot`；github 的
`GitHubTaskReferenceAdapter` 用 `workspaceId + projectId + localSnapshotId` 读取 Issue/PR Snapshot，返回
Binding、Repository、GitHub stable object ID、number、标题、状态和 URL，不返回 Body，也不把 GitHub login
映射为本地用户。

```text
framework <- identity <- project <- task <- github adapter
```

## Task 表与状态机

V10 新增 `dp_task`、`dp_task_status_history`、`dp_task_github_link`。Display Key 不存储，运行时计算为
`projectKey + "-" + task.id`。初始 Task `version=0`，CREATED History `task_version=0`；其后每次受控写入
version 加一。History 不可通过普通 API 修改/删除，`UNIQUE(task_id, task_version)` 防止同一版本有两条状态事实。

```text
BACKLOG --plan--> TODO --start--> IN_PROGRESS --submit--> IN_REVIEW --complete--> DONE
                 ^                    ^                     |                       |
                 +--returnToBacklog---+--requestChanges-----+                       +--reopen--> TODO
任何非终态 --cancel--> CANCELED --reopen--> TODO
```

没有通用 `updateStatus`。每个动作都验证前置状态、权限和 expectedVersion，并用同一条含 scope、status、
`deleted=0`、version 的条件 UPDATE 抢占。DONE 设置 `completed_at`，CANCELED 设置 `canceled_at`，reopen 清空二者。
Task、状态 History 与确定性 Project Activity 必须同事务提交，否则 version 的状态事实与审计链会分叉。

## RBAC 与属性规则

Manager 是 Workspace OWNER/ADMIN 或 Project PROJECT_ADMIN。Controller 不接受 currentUserId；身份只来自
`CurrentUserProvider`。

| 操作 | Permission | 属性规则 |
|---|---|---|
| 创建/读取 | TASK_CREATE / TASK_READ | Workspace ACTIVE、Project 非 ARCHIVED |
| 资料更新/关联 | TASK_UPDATE | Reporter、Assignee 或 Manager |
| 分配 | TASK_ASSIGN | 仅 Manager；目标 ACTIVE 且有 TASK_READ |
| plan/return | TASK_STATUS_CHANGE | Reporter、Assignee 或 Manager |
| start/submit | TASK_STATUS_CHANGE | Assignee 或 Manager |
| changes/complete/reopen | TASK_STATUS_CHANGE | 仅 Manager |
| cancel | TASK_STATUS_CHANGE | Manager；或 Reporter 在 BACKLOG/TODO |

当前策略明确**不允许 Reporter 分配其他用户**。终态 Task 的普通关联被拒绝，Manager 才可按受控规则关联。

## GitHub Link 与 Activity

关联请求只提交本地 Snapshot ID。服务端 Port 回填稳定 `github_object_id`；number/URL 仅展示，客户端不能伪造。
Issue 默认 `TRACKS`，PR 默认 `IMPLEMENTED_BY`，`RELATED_TO` 需要显式提交。

`active_external_identity = resource_type + github_repository_id + github_object_id` 仅在 ACTIVE 时生成，唯一键是
同一个外部对象最多关联一个有效 Task 的最终并发防线。remove 只写 `REMOVED`、actor/time/version，不物理删除；
生成列变 NULL 后允许重新关联。

Task Activity 使用 `task:{taskId}:v{version}`，`ProjectActivityService.recordTaskActivity` 是明确入口，Task 不会伪造
GitHub Delivery。Activity 来源唯一键是重复执行时的最后保护。

`createTaskFromIssue` 是显式动作：只接收 Issue Snapshot ID 和可选 priority/due/assignee，安全标题来自 Issue，
description 仅为“关联 GitHub Issue #N”，不复制 Body。ACTIVE Link 冲突返回
`TASK_EXTERNAL_RESOURCE_ALREADY_LINKED`，整个 Task/History/Activity/Link 事务回滚；不会自动导入仓库所有 Issue。

## 手动验证

登录后使用 Bearer Token，准备未归档 Workspace/Project。不要在命令、日志或数据库中放 PAT、Webhook Secret 或
完整私有 Issue Body。

1. `POST .../tasks` body `{"title":"设计状态机"}`：预期 BACKLOG/version 0、CREATED History、TASK_CREATED Activity。
2. `POST .../tasks/{id}/assign` body `{"assigneeUserId":2,"expectedVersion":0}`：预期 version 1 与 TASK_ASSIGNED，
   没有新 History。
3. 按 `plan → start → submit-for-review → request-changes → submit-for-review → complete` 调用，预期
   TODO/IN_PROGRESS/IN_REVIEW/IN_PROGRESS/IN_REVIEW/DONE，每步 version+1 且一条 History/Activity。
4. BACKLOG 调 complete 应为 409 `TASK_INVALID_TRANSITION`；带旧 version 重发应为 409 `TASK_VERSION_CONFLICT`。
5. `POST .../tasks/from-github-issue/{issueSnapshotId}`：预期 BACKLOG、TRACKS ACTIVE Link；SQL：
   `SELECT * FROM dp_task_github_link WHERE task_id=?`。
6. `POST .../github-links` 关联 PR 后即使 PR MERGED，Task 状态不变。remove Link 后再次 link 成功。
7. SQL 核对：`SELECT * FROM dp_task_status_history WHERE task_id=?` 与
   `SELECT * FROM dp_project_activity WHERE source_type='TASK' AND source_delivery_id LIKE 'task:%'`。

本节尚未实现截止提醒、通知、审计表、GitHub 写 API、自动状态同步或消息队列。
